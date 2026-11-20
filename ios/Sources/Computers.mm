// SPDX-License-Identifier: GPL-3.0-or-later
#import "Computers.h"
#import <Security/Security.h>
#include "../Pairing/Pairing.hpp"
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
    std::atomic<bool> canceled{false};std::atomic<int> action{0};std::mutex mutex;int fd=-1;
    void stop(){canceled=true;std::lock_guard<std::mutex> lock(mutex);if(fd>=0)shutdown(fd,SHUT_RDWR);}
    void closeSocket(){std::lock_guard<std::mutex> lock(mutex);if(fd>=0){close(fd);fd=-1;}}
    ~Run(){closeSocket();}
};
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
    self.navigationItem.rightBarButtonItem=[[UIBarButtonItem alloc] initWithTitle:NSLocalizedString(@"disconnect",nil) style:UIBarButtonItemStylePlain target:self action:@selector(disconnect)];
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
- (void)viewWillDisappear:(BOOL)animated {[super viewWillDisappear:animated];[self disconnect];}
- (void)dealloc {[[NSNotificationCenter defaultCenter] removeObserver:self];[_browser stop];if(_run)_run->stop();}
- (void)disconnect {
    ++_generation;if(_run)_run->stop();_run.reset();[_browser stop];
    for(NSNetService* service in _services){service.delegate=nil;[service stop];}
    [_services removeAllObjects];[_peers removeAllObjects];[self.tableView reloadData];
    if(self.presentedViewController)[self dismissViewControllerAnimated:NO completion:nil];_status.text=NSLocalizedString(@"disconnected",nil);
}
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
    _status.text=NSLocalizedString(@"choose_computer",nil);
}
- (NSArray<UITableViewRowAction*>*)tableView:(UITableView*)view editActionsForRowAtIndexPath:(NSIndexPath*)path {
    NSDictionary* peer=_peers[path.row];const auto peerId=bytes(peer[@"id"]);
    if(!_store["pins"].contains(peerId))return @[];
    __weak FHComputersController* weak=self;
    return @[[UITableViewRowAction rowActionWithStyle:UITableViewRowActionStyleDestructive title:NSLocalizedString(@"forget_pairing",nil) handler:^(UITableViewRowAction* action,NSIndexPath* index){
        FHComputersController* strong=weak;if(!strong)return;
        try {strong->_store["pins"].erase(peerId);saveStore(strong->_store);if([strong->_connectedId isEqual:peer[@"id"]] && strong->_run)strong->_run->action=3;[strong.tableView reloadData];}
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
    const Identity identity=_identity;NSArray<NSData*>* addresses=[service.addresses copy];__weak FHComputersController* weak=self;
    _status.text=NSLocalizedString(@"connecting",nil);
    std::thread([run,identity,peerId,pin,addresses,weak,generation]{@autoreleasepool {
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
            PairingState state(!pin.empty());
            auto notify=[&](bool trusted){const std::string verifiedPin=certificatePin(channel.certificate());
                __weak FHComputersController* recipient=weak;const auto task=run;const auto epoch=generation;const auto idCopy=peerId;
                dispatch_async(dispatch_get_main_queue(), ^{
                FHComputersController* strong=recipient;if(!strong || strong->_generation!=epoch || task->canceled)return;
                try {if(trusted)strong->_store["pins"][idCopy]=verifiedPin;else strong->_store["pins"].erase(idCopy);saveStore(strong->_store);strong->_status.text=trusted?NSLocalizedString(@"paired_pending_receiver",nil):NSLocalizedString(@"disconnected",nil);}
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
                if(action==2 || action==3){channel.send(packet("kdeconnect.pair",{{"pair",false}}));state.reset();if(action==3)notify(false);break;}
                if(action==1){const auto confirmation=state.confirm(mono());if(!confirmation.is_null())channel.send(confirmation);if(state.phase==PairingState::Phase::Paired)notify(true);}
                if(state.expire(mono())){channel.send(packet("kdeconnect.pair",{{"pair",false}}));throw std::runtime_error("Pairing timed out");}
                Json value;if(!channel.receive(value,200))continue;
                if(value["type"]!="kdeconnect.pair")continue; // Plugin traffic is not yet implemented.
                const auto before=state.phase;state.incoming(value["body"],wall(),mono());
                if(before==PairingState::Phase::Paired && state.phase!=before)notify(false);
                if(state.phase==PairingState::Phase::ConfirmIncoming)prompt();
                else if(state.phase==PairingState::Phase::Paired)notify(true);
                else if(state.phase==PairingState::Phase::Unpaired)break;
            }
            run->closeSocket();
            __weak FHComputersController* recipient=weak;const auto epoch=generation;
            dispatch_async(dispatch_get_main_queue(), ^{FHComputersController* strong=recipient;if(strong && strong->_generation==epoch){if(strong.presentedViewController)[strong dismissViewControllerAnimated:YES completion:nil];strong->_status.text=NSLocalizedString(@"disconnected",nil);}});
        }catch(...) {run->closeSocket();__weak FHComputersController* recipient=weak;const auto epoch=generation;const auto task=run;
            dispatch_async(dispatch_get_main_queue(), ^{FHComputersController* strong=recipient;if(strong && strong->_generation==epoch && !task->canceled){if(strong.presentedViewController)[strong dismissViewControllerAnimated:YES completion:nil];[strong failure];}});}
    }}).detach();
}
@end
