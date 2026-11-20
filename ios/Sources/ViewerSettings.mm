// SPDX-License-Identifier: GPL-3.0-or-later
#import "ViewerSettings.h"
#include <sys/utsname.h>
#include <algorithm>
using namespace flatheadset;
namespace {
std::shared_ptr<const Viewer> prepare(NSInteger mode,NSString* link,UIScreen* screen){
    struct utsname system{};
    const float dpi=uname(&system)==0?phoneDpi(system.machine):0;
    const CGRect bounds=screen.nativeBounds;
    return makeViewer(static_cast<LensMode>(mode),link.UTF8String ?: "",
        static_cast<int>(std::max(bounds.size.width,bounds.size.height)),
        static_cast<int>(std::min(bounds.size.width,bounds.size.height)),dpi);
}
void showFailure(UIViewController* parent){
    UIAlertController* alert=[UIAlertController alertControllerWithTitle:NSLocalizedString(@"viewer_title",nil) message:NSLocalizedString(@"viewer_invalid",nil) preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"decoder_go_back",nil) style:UIAlertActionStyleCancel handler:nil]];
    [parent presentViewController:alert animated:YES completion:nil];
}
void save(UIViewController* parent,NSInteger mode,NSString* link,void (^changed)(void)){
    try {prepare(mode,link,parent.view.window.screen);}
    catch(...){showFailure(parent);return;}
    NSUserDefaults* prefs=NSUserDefaults.standardUserDefaults;
    [prefs setInteger:mode forKey:@"viewer.mode"];
    [prefs setObject:link ?: @"" forKey:@"viewer.qr"];
    if(changed)changed();
}
}
@implementation FHViewerSettings
+ (std::shared_ptr<const Viewer>)viewerForScreen:(UIScreen*)screen {
    NSUserDefaults* prefs=NSUserDefaults.standardUserDefaults;
    return prepare([prefs integerForKey:@"viewer.mode"],[prefs stringForKey:@"viewer.qr"],screen);
}
+ (NSString*)name {
    switch([NSUserDefaults.standardUserDefaults integerForKey:@"viewer.mode"]){
        case 0:return NSLocalizedString(@"viewer_none",nil);
        case 1:return NSLocalizedString(@"viewer_fitted",nil);
        case 2:return NSLocalizedString(@"viewer_qr",nil);
        default:return NSLocalizedString(@"viewer_invalid",nil);
    }
}
+ (void)chooseFrom:(UIViewController*)parent changed:(void (^)(void))changed {
    UIAlertController* menu=[UIAlertController alertControllerWithTitle:NSLocalizedString(@"viewer_title",nil) message:[self name] preferredStyle:UIAlertControllerStyleAlert];
    [menu addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_none",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* a){save(parent,0,@"",changed);}]];
    [menu addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_fitted",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* a){
        UIAlertController* fit=[UIAlertController alertControllerWithTitle:NSLocalizedString(@"viewer_fitted",nil) message:NSLocalizedString(@"viewer_fit_note",nil) preferredStyle:UIAlertControllerStyleAlert];
        [fit addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_use",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* b){save(parent,1,@"",changed);}]];
        [fit addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"decoder_go_back",nil) style:UIAlertActionStyleCancel handler:nil]];
        [parent presentViewController:fit animated:YES completion:nil];
    }]];
    [menu addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_v_one",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* a){save(parent,2,@"",changed);}]];
    [menu addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_import",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* a){
        UIAlertController* input=[UIAlertController alertControllerWithTitle:NSLocalizedString(@"viewer_import",nil) message:NSLocalizedString(@"viewer_qr_hint",nil) preferredStyle:UIAlertControllerStyleAlert];
        [input addTextFieldWithConfigurationHandler:^(UITextField* field){field.text=[NSUserDefaults.standardUserDefaults stringForKey:@"viewer.qr"];field.autocorrectionType=UITextAutocorrectionTypeNo;field.autocapitalizationType=UITextAutocapitalizationTypeNone;field.keyboardType=UIKeyboardTypeURL;}];
        __weak UIAlertController* weakInput=input;
        [input addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"viewer_use",nil) style:UIAlertActionStyleDefault handler:^(UIAlertAction* b){
            NSString* link=weakInput.textFields.firstObject.text;
            if(!link.length){showFailure(parent);return;}save(parent,2,link,changed);
        }]];
        [input addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"decoder_go_back",nil) style:UIAlertActionStyleCancel handler:nil]];
        [parent presentViewController:input animated:YES completion:nil];
    }]];
    [menu addAction:[UIAlertAction actionWithTitle:NSLocalizedString(@"decoder_go_back",nil) style:UIAlertActionStyleCancel handler:nil]];
    [parent presentViewController:menu animated:YES completion:nil];
}
@end
