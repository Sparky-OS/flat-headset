// SPDX-License-Identifier: GPL-3.0-or-later
#import "Computers.h"
#import "Desktop.h"
#import <Security/Security.h>
#include "../Pairing/Pairing.hpp"
#include "../Session/RdpSession.hpp"
#include <netdb.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <mutex>
#include <thread>
#include <stdexcept>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>
using namespace flatheadset;

namespace {
std::mutex identityMutex;
NSString* text(const std::string& s) { return [[NSString alloc] initWithBytes:s.data() length:s.size() encoding:NSUTF8StringEncoding] ?: @""; }
std::string bytes(NSString* s) { const char* p=s.UTF8String;return p?std::string(p):std::string(); }
Json loadStore() {
    NSDictionary* query=@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
      (__bridge id)kSecAttrService:@"org.sparkyos.flatheadset.pairing",(__bridge id)kSecAttrAccount:@"identity-and-pins",
      (__bridge id)kSecReturnData:@YES,(__bridge id)kSecMatchLimit:(__bridge id)kSecMatchLimitOne};
    CFTypeRef raw=nullptr;OSStatus status=SecItemCopyMatching((__bridge CFDictionaryRef)query,&raw);
    if(status==errSecItemNotFound)return Json();
    if(status!=errSecSuccess)throw std::runtime_error("Keychain read failed");
    NSData* data=CFBridgingRelease(raw);if(data.length>1024*1024)throw std::runtime_error("Keychain size limit");
    return Json::parse(std::string(static_cast<const char*>(data.bytes),data.length));
}
void saveStore(const Json& value) {
    const auto json=value.dump();NSData* data=[NSData dataWithBytes:json.data() length:json.size()];
    NSDictionary* query=@{(__bridge id)kSecClass:(__bridge id)kSecClassGenericPassword,
      (__bridge id)kSecAttrService:@"org.sparkyos.flatheadset.pairing",(__bridge id)kSecAttrAccount:@"identity-and-pins"};
    NSDictionary* update=@{(__bridge id)kSecValueData:data,(__bridge id)kSecAttrAccessible:(__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly};
    OSStatus status=SecItemUpdate((__bridge CFDictionaryRef)query,(__bridge CFDictionaryRef)update);
    if(status==errSecItemNotFound) {NSMutableDictionary* insert=[query mutableCopy];[insert addEntriesFromDictionary:update];status=SecItemAdd((__bridge CFDictionaryRef)insert,nullptr);}
    if(status!=errSecSuccess)throw std::runtime_error("Keychain write failed");
}
struct Run {
    std::atomic<bool> canceled{false}, approved{false};std::atomic<int> action{0};std::mutex mutex;int fd=-1;
    std::shared_ptr<RdpSession> rdp;
    void stopRdp(){std::shared_ptr<RdpSession> session;{std::lock_guard<std::mutex> lock(mutex);session=rdp;}if(session)session->stop();}
    void stop(){canceled=true;approved=false;stopRdp();std::lock_guard<std::mutex> lock(mutex);if(fd>=0)shutdown(fd,SHUT_RDWR);}
    void closeSocket(){std::lock_guard<std::mutex> lock(mutex);if(fd>=0){close(fd);fd=-1;}}
    ~Run(){closeSocket();}
};
NSString* sessionText(SessionStatus status) {
    switch(status) {
        case SessionStatus::Untrusted:return NSLocalizedString(@"session_untrusted",nil);
        case SessionStatus::Unavailable:return NSLocalizedString(@"session_unavailable",nil);
        case SessionStatus::Idle:return NSLocalizedString(@"session_idle",nil);
        case SessionStatus::Waiting:return NSLocalizedString(@"session_waiting",nil);
        case SessionStatus::Permission:return NSLocalizedString(@"session_permission",nil);
        case SessionStatus::Starting:return NSLocalizedString(@"session_starting",nil);
        case SessionStatus::Connecting:return NSLocalizedString(@"session_connecting",nil);
        case SessionStatus::Connected:return NSLocalizedString(@"session_connected",nil);
        case SessionStatus::Refused:return NSLocalizedString(@"session_refused",nil);
        case SessionStatus::Invalid:return NSLocalizedString(@"session_invalid",nil);
        case SessionStatus::Certificate:return NSLocalizedString(@"session_certificate",nil);
        case SessionStatus::Failed:return NSLocalizedString(@"session_failed",nil);
        case SessionStatus::Timeout:return NSLocalizedString(@"session_timeout",nil);
        case SessionStatus::Stopped:return NSLocalizedString(@"session_stopped",nil);
    }
    return NSLocalizedString(@"session_failed",nil);
}
int64_t wall(){return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
int64_t mono(){return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now().time_since_epoch()).count();}
}

@interface FHComputersController () <NSNetServiceBrowserDelegate,NSNetServiceDelegate>
@end
@implementation FHComputersController {
    NSNetServiceBrowser* _browser;
    NSMutableArray<NSNetService*>* _services;
    NSMutableArray<NSDictionary*>* _peers;
    UILabel* _status;
    __weak FHDesktopController* _desktop;
    Json _store;
    Identity _identity;
    std::shared_ptr<Run> _run;
    NSUInteger _generation;
    BOOL _ready;
    NSString* _connectedId;
}
- (void)viewDidLoad {
    [super viewDidLoad];self.title=NSLocalizedString(@"computers",nil);
    self.navigationItem.leftBarButtonItem=[[UIBarButtonItem alloc] initWithBarButtonSystemItem:UIBarButtonSystemItemDone target:self action:@selector(closeComputers)];
    _services=[NSMutableArray array];_peers=[NSMutableArray array];
    _status=[[UILabel alloc] initWithFrame:CGRectMake(0,0,500,90)];_status.numberOfLines=0;_status.textAlignment=NSTextAlignmentCenter;
    _status.text=NSLocalizedString(@"finding_computers",nil);self.tableView.tableHeaderView=_status;
    self.navigationItem.rightBarButtonItems=@[
      [[UIBarButtonItem alloc] initWithTitle:NSLocalizedString(@"disconnect",nil) style:UIBarButtonItemStylePlain target:self action:@selector(disconnect)],
      [[UIBarButtonItem alloc] initWithTitle:NSLocalizedString(@"catch_desktop",nil) style:UIBarButtonItemStylePlain target:self action:@selector(catchDesktop)],
      [[UIBarButtonItem alloc] initWithTitle:NSLocalizedString(@"stop_session",nil) style:UIBarButtonItemStylePlain target:self action:@selector(stopSession)]];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(disconnect) name:UIApplicationDidEnterBackgroundNotification object:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self selector:@selector(becameActive) name:UIApplicationDidBecomeActiveNotification object:nil];
    // Key generation is bounded but deliberately kept off the UI thread.
    __weak FHComputersController* weak=self;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0), ^{
        try {
            std::lock_guard<std::mutex> lock(identityMutex);
            Json store=loadStore();Identity identity;
            if(store.is_null()) {identity=makeIdentity();store={{"id",identity.id},{"certificate",identity.certificate},{"key",identity.key},{"pins",Json::object()}};saveStore(store);}
            else {identity={store.at("id").get<std::string>(),store.at("certificate").get<std::string>(),store.at("key").get<std::string>()};if(!store.at("pins").is_object())throw std::runtime_error("Invalid trust store");}
            dispatch_async(dispatch_get_main_queue(), ^{FHComputersController* strong=weak;if(!strong)return;strong->_store=store;strong->_identity=identity;strong->_ready=YES;[strong discover];});
        } catch(...) {dispatch_async(dispatch_get_main_queue(), ^{[weak failure];});}
    });
}
- (void)closeComputers {[self disconnect];[self dismissViewControllerAnimated:YES completion:nil];}
- (void)becameActive {if(self.view.window && self.navigationController.topViewController==self)[self discover];}
- (void)discover {
    if(!_ready || !self.view.window || UIApplication.sharedApplication.applicationState!=UIApplicationStateActive)return;
    [_browser stop];_browser=[NSNetServiceBrowser new];_browser.delegate=self;
    [_browser searchForServicesOfType:@"_kdeconnect._udp." inDomain:@"local."];
}
- (void)viewDidAppear:(BOOL)animated {[super viewDidAppear:animated];if(_ready)[self discover];}
- (void)viewWillDisappear:(BOOL)animated {[super viewWillDisappear:animated];if(self.isBeingDismissed || self.isMovingFromParentViewController || self.navigationController.isBeingDismissed)[self disconnect];}
- (void)dealloc {[[NSNotificationCenter defaultCenter] removeObserver:self];[_browser stop];if(_run)_run->stop();}
- (void)disconnect {
    [_desktop showSessionStatus:NSLocalizedString(@"disconnected",nil) terminal:YES];
    ++_generation;if(_run)_run->stop();_run.reset();[_browser stop];
    for(NSNetService* service in _services){service.delegate=nil;[service stop];}
    [_services removeAllObjects];[_peers removeAllObjects];[self.tableView reloadData];
    if(self.presentedViewController)[self dismissViewControllerAnimated:NO completion:nil];_status.text=NSLocalizedString(@"disconnected",nil);
}
- (void)catchDesktop {if(_run && _run->approved && !_run->canceled)_run->action=4;else _status.text=sessionText(SessionStatus::Untrusted);}
- (void)stopSession {if(_run){_run->stopRdp();_run->action=5;}}
- (void)failure {_status.text=NSLocalizedString(@"pairing_failed",nil);}
- (void)netServiceBrowser:(NSNetServiceBrowser*)browser didNotSearch:(NSDictionary<NSString*,NSNumber*>*)error {[self failure];}
- (void)netServiceBrowser:(NSNetServiceBrowser*)browser didFindService:(NSNetService*)service moreComing:(BOOL)more {
    if(_services.count>=64)return;[_services addObject:service];service.delegate=self;[service resolveWithTimeout:5];
}
- (void)netServiceBrowser:(NSNetServiceBrowser*)browser didRemoveService:(NSNetService*)service moreComing:(BOOL)more {
    [_services removeObject:service];NSIndexSet* indexes=[_peers indexesOfObjectsPassingTest:^BOOL(NSDictionary* p,NSUInteger i,BOOL* stop){return [p[@"service"] isEqual:service];}];[_peers removeObjectsAtIndexes:indexes];[self.tableView reloadData];
}
- (void)netServiceDidResolveAddress:(NSNetService*)service {
    NSDictionary* txt=[NSNetService dictionaryFromTXTRecordData:service.TXTRecordData ?: [NSData data]];
    NSString* peerId=[[NSString alloc] initWithData:txt[@"id"] ?: [NSData data] encoding:NSUTF8StringEncoding];
    NSString* version=[[NSString alloc] initWithData:txt[@"protocol"] ?: [NSData data] encoding:NSUTF8StringEncoding];
    NSString* peerName=[[NSString alloc] initWithData:txt[@"name"] ?: [NSData data] encoding:NSUTF8StringEncoding];
    if(!peerId.length || [peerId isEqualToString:text(_identity.id)] || ![version isEqualToString:@"8"] || !service.addresses.count || service.port<1716 || service.port>1764)return;
    if(!peerName.length || peerName.length>128 || [peerName rangeOfCharacterFromSet:NSCharacterSet.controlCharacterSet].location!=NSNotFound)peerName=peerId;
    for(NSDictionary* old in _peers)if([old[@"id"] isEqual:peerId])return;
    [_peers addObject:@{@"id":peerId,@"name":peerName,@"service":service}];[self.tableView reloadData];
    if(!_run || !_run->approved)_status.text=NSLocalizedString(@"choose_computer",nil);
}
- (NSArray<UITableViewRowAction*>*)tableView:(UITableView*)view editActionsForRowAtIndexPath:(NSIndexPath*)path {
    NSDictionary* peer=_peers[path.row];const auto peerId=bytes(peer[@"id"]);
    if(!_store["pins"].contains(peerId))return @[];
    __weak FHComputersController* weak=self;
    return @[[UITableViewRowAction rowActionWithStyle:UITableViewRowActionStyleDestructive title:NSLocalizedString(@"forget_pairing",nil) handler:^(UITableViewRowAction* action,NSIndexPath* index){
        FHComputersController* strong=weak;if(!strong)return;
        try {strong->_store["pins"].erase(peerId);saveStore(strong->_store);if([strong->_connectedId isEqual:peer[@"id"]] && strong->_run){strong->_run->approved=false;strong->_run->stopRdp();strong->_run->action=3;}[strong.tableView reloadData];}
        catch(...){[strong failure];}
    }]];
}
- (NSInteger)tableView:(UITableView*)view numberOfRowsInSection:(NSInteger)section {return _peers.count;}
- (UITableViewCell*)tableView:(UITableView*)view cellForRowAtIndexPath:(NSIndexPath*)path {
    UITableViewCell* cell=[[UITableViewCell alloc] initWithStyle:UITableViewCellStyleSubtitle reuseIdentifier:nil];NSDictionary* peer=_peers[path.row];cell.textLabel.text=peer[@"name"];
    cell.detailTextLabel.text=NSLocalizedString(@"pair_computer",nil);return cell;
}
- (void)tableView:(UITableView*)view didSelectRowAtIndexPath:(NSIndexPath*)path {
    if(!_ready)return;NSDictionary* peer=_peers[path.row];NSNetService* service=peer[@"service"];
    const std::string peerId=bytes(peer[@"id"]);std::string pin;
    try {if(_store["pins"].contains(peerId))pin=_store["pins"].at(peerId).get<std::string>();}catch(...){[self failure];return;}
    if(_run)_run->stop();auto run=std::make_shared<Run>();_run=run;const NSUInteger generation=++_generation;
    _connectedId=peer[@"id"];
    UIScreen* screen=self.view.window.screen;
    const CGRect bounds=screen.nativeBounds;
    const PanelSize panel{static_cast<int>(std::max(bounds.size.width,bounds.size.height)),static_cast<int>(std::min(bounds.size.width,bounds.size.height)),static_cast<int>(screen.maximumFramesPerSecond*1000)};
    const Identity identity=_identity;NSArray<NSData*>* addresses=[service.addresses copy];__weak FHComputersController* weak=self;
    _status.text=NSLocalizedString(@"connecting",nil);
    std::thread([run,identity,peerId,pin,addresses,weak,generation,panel]{@autoreleasepool {
        bool sessionPhase=false;
        try {
            for(NSData* address in addresses) {
                if(run->canceled)break;
                if(address.length<sizeof(sockaddr) || address.length>sizeof(sockaddr_storage))continue;
                const auto* sa=static_cast<const sockaddr*>(address.bytes);
                if(sa->sa_family!=AF_INET && sa->sa_family!=AF_INET6)continue;
                int fd=socket(sa->sa_family,SOCK_STREAM,0);if(fd<0)continue;
                {std::lock_guard<std::mutex> lock(run->mutex);run->fd=fd;}
                int flags=fcntl(fd,F_GETFL,0);if(flags<0 || fcntl(fd,F_SETFL,flags|O_NONBLOCK)<0)throw std::runtime_error("Socket setup failed");
                int result=connect(fd,sa,static_cast<socklen_t>(address.length));
                if(result<0 && errno==EINPROGRESS){pollfd p{fd,POLLOUT,0};result=poll(&p,1,5000);int error=0;socklen_t size=sizeof(error);if(result>0 && getsockopt(fd,SOL_SOCKET,SO_ERROR,&error,&size)==0 && !error)result=0;else result=-1;}
                if(result==0 && !run->canceled){if(fcntl(fd,F_SETFL,flags)<0)throw std::runtime_error("Socket setup failed");break;}
                run->closeSocket();
            }
            if(run->canceled || run->fd<0)throw std::runtime_error("Connection canceled");
            PairingChannel channel(run->fd,identity);channel.handshake(peerId,pin);
            sockaddr_storage endpoint{};socklen_t length=sizeof(endpoint);char host[NI_MAXHOST];
            if(getpeername(run->fd,reinterpret_cast<sockaddr*>(&endpoint),&length)!=0 ||
               getnameinfo(reinterpret_cast<sockaddr*>(&endpoint),length,host,sizeof(host),nullptr,0,NI_NUMERICHOST)!=0)throw std::runtime_error("Peer address unavailable");
            const auto verifiedPin=certificatePin(channel.certificate());
            SessionControl control(peerId,verifiedPin,certificatePin(identity.certificate),host,panel);
            PairingState state(!pin.empty());bool authorized=false;SessionStatus shown=SessionStatus::Untrusted;
            auto publish=[&](SessionStatus status){
                __weak FHComputersController* recipient=weak;const auto epoch=generation;const auto task=run;
                std::shared_ptr<Frames> frames;
                {std::lock_guard<std::mutex> lock(run->mutex);if(run->rdp)frames=run->rdp->frames();}
                dispatch_async(dispatch_get_main_queue(), ^{
                    FHComputersController* strong=recipient;if(!strong || strong->_generation!=epoch || task->canceled)return;
                    NSString* message=sessionText(status);strong->_status.text=message;
                    const BOOL running=status==SessionStatus::Connecting || status==SessionStatus::Connected;
                    if(running && frames && frames->active() && !strong->_desktop && strong.navigationController.topViewController==strong) {
                        FHDesktopController* desktop=[[FHDesktopController alloc] initWithFrames:frames];
                        desktop.onStop=^{task->stopRdp();task->action=5;};strong->_desktop=desktop;
                        [strong.navigationController pushViewController:desktop animated:NO];
                    }
                    [strong->_desktop showSessionStatus:message terminal:!running];
                });
            };
            auto send=[&](const Json& value){if(!value.is_null())channel.send(value);};
            auto finish=[&](SessionStatus status){
                std::shared_ptr<RdpSession> session;
                {std::lock_guard<std::mutex> lock(run->mutex);session=std::move(run->rdp);}
                if(session)session->stop();send(control.stop(status));
            };
            auto notify=[&](bool trusted){const std::string verifiedPin=certificatePin(channel.certificate());
                __weak FHComputersController* recipient=weak;const auto task=run;const auto epoch=generation;const auto idCopy=peerId;
                dispatch_async(dispatch_get_main_queue(), ^{
                FHComputersController* strong=recipient;if(!strong || strong->_generation!=epoch || task->canceled)return;
                try {if(trusted)strong->_store["pins"][idCopy]=verifiedPin;else strong->_store["pins"].erase(idCopy);saveStore(strong->_store);task->approved=trusted;strong->_status.text=trusted?sessionText(SessionStatus::Idle):NSLocalizedString(@"disconnected",nil);}
                catch(...){task->stop();[strong failure];}
            });};
            auto prompt=[&]{const auto code=verificationKey(identity.certificate,channel.certificate(),state.timestamp);const auto peerName=channel.peerName();
                __weak FHComputersController* recipient=weak;const auto task=run;const auto epoch=generation;
                dispatch_async(dispatch_get_main_queue(), ^{
                FHComputersController* strong=recipient;if(!strong || strong->_generation!=epoch || task->canceled)return;
                NSString* message=[NSString stringWithFormat:NSLocalizedString(@"compare_code",nil),text(peerName),text(code)];
                UIAlertController* alert=[UIAlertController alertControllerWithTitle:NSLocalizedString(@"pair_computer",nil) message:message preferredStyle:UIAlertControllerStyleAlert];
                [alert addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"cancel_pairing",nil) style:UIAlertActionStyleCancel handler:^(UIAlertAction* a){task->action=2;}]];
                [alert addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"confirm_pairing",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* a){task->action=1;}]];
                [strong presentViewController:alert animated:YES completion:nil];
            });};
            if(state.phase==PairingState::Phase::Paired)notify(true);else {state.offer(wall(),mono());channel.send(packet("kdeconnect.pair",{{"pair",true},{"timestamp",state.timestamp}}));prompt();}
            while(!run->canceled) {
                const int action=run->action.exchange(0);
                if(action==2 || action==3){run->approved=false;finish(SessionStatus::Untrusted);control.revoke();channel.send(packet("kdeconnect.pair",{{"pair",false}}));state.reset();if(action==3)notify(false);break;}
                if(action==1){const auto confirmation=state.confirm(mono());if(!confirmation.is_null())channel.send(confirmation);if(state.phase==PairingState::Phase::Paired)notify(true);}
                if(state.expire(mono())){channel.send(packet("kdeconnect.pair",{{"pair",false}}));throw std::runtime_error("Pairing timed out");}
                if(state.phase==PairingState::Phase::Paired && run->approved && !authorized) {
                    control.authorize(verifiedPin);authorized=true;sessionPhase=true;send(control.capabilities());send(control.capabilityRequest());
                }
                if(action==4 && run->approved)send(control.request(mono()));
                if(action==5)finish(SessionStatus::Stopped);
                if(control.expire(mono()))finish(SessionStatus::Timeout);
                if(run->rdp) {
                    const auto rdpState=run->rdp->state();
                    if(rdpState==RdpSession::State::Connected)send(control.connected());
                    else if(rdpState==RdpSession::State::CertificateRejected)finish(SessionStatus::Certificate);
                    else if(rdpState==RdpSession::State::Refused)finish(SessionStatus::Refused);
                    else if(rdpState==RdpSession::State::Failed)finish(SessionStatus::Failed);
                    else if(rdpState==RdpSession::State::Stopped)finish(SessionStatus::Stopped);
                }
                if(authorized && shown!=control.status()){shown=control.status();publish(shown);}
                Json value;if(!channel.receive(value,200))continue;
                if(value["type"]!="kdeconnect.pair") {
                    if(value["type"]!="kdeconnect.virtualmonitor" && value["type"]!="kdeconnect.virtualmonitor.request")continue;
                    if(!authorized || !run->approved){publish(SessionStatus::Untrusted);continue;}
                    control.receive(value,mono());
                    if(auto grant=control.takeGrant()) {
                        auto session=std::make_shared<RdpSession>(std::move(grant));
                        {std::lock_guard<std::mutex> lock(run->mutex);
                            // Forget/background may race the reply. Publish and start under
                            // the same lock that stopRdp uses to obtain the active adapter.
                            if(!run->approved || run->canceled){control.revoke();authorized=false;session->stop();continue;}
                            run->rdp=session;session->start();
                        }
                    }
                    const auto status=control.status();
                    if(status==SessionStatus::Invalid || status==SessionStatus::Refused || status==SessionStatus::Stopped || status==SessionStatus::Unavailable || status==SessionStatus::Timeout)finish(status);
                    continue;
                }
                const auto before=state.phase;state.incoming(value["body"],wall(),mono());
                if(before==PairingState::Phase::Paired && state.phase!=before){run->approved=false;finish(SessionStatus::Untrusted);control.revoke();authorized=false;notify(false);}
                if(state.phase==PairingState::Phase::ConfirmIncoming)prompt();
                else if(state.phase==PairingState::Phase::Paired && before!=state.phase)notify(true);
                else if(state.phase==PairingState::Phase::Unpaired)break;
            }
            run->approved=false;control.revoke();run->stopRdp();run->closeSocket();
            __weak FHComputersController* recipient=weak;const auto epoch=generation;
            dispatch_async(dispatch_get_main_queue(), ^{FHComputersController* strong=recipient;if(strong && strong->_generation==epoch){if(strong.presentedViewController)[strong dismissViewControllerAnimated:YES completion:nil];strong->_status.text=NSLocalizedString(@"disconnected",nil);[strong->_desktop showSessionStatus:strong->_status.text terminal:YES];}});
        }catch(...) {run->approved=false;run->stopRdp();run->closeSocket();__weak FHComputersController* recipient=weak;const auto epoch=generation;const auto task=run;const bool failedSession=sessionPhase;
            dispatch_async(dispatch_get_main_queue(), ^{FHComputersController* strong=recipient;if(strong && strong->_generation==epoch && !task->canceled){if(strong.presentedViewController)[strong dismissViewControllerAnimated:YES completion:nil];if(failedSession){strong->_status.text=sessionText(SessionStatus::Failed);[strong->_desktop showSessionStatus:strong->_status.text terminal:YES];}else [strong failure];}});}
    }}).detach();
}
@end
