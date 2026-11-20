// SPDX-License-Identifier: GPL-3.0-or-later
#import <UIKit/UIKit.h>
#include "../Viewer/Viewer.hpp"
@interface FHViewerSettings : NSObject
+ (std::shared_ptr<const flatheadset::Viewer>)viewerForScreen:(UIScreen*)screen;
+ (NSString*)name;
+ (void)importLink:(NSString*)link from:(UIViewController*)parent;
+ (void)chooseFrom:(UIViewController*)parent changed:(void (^)(void))changed;
@end
