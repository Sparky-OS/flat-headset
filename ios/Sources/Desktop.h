// SPDX-License-Identifier: GPL-3.0-or-later
#import <UIKit/UIKit.h>
#include "../Session/Frames.hpp"
@interface FHDesktopController : UIViewController
- (instancetype)initWithFrames:(std::shared_ptr<flatheadset::Frames>)frames;
- (void)showSessionStatus:(NSString*)status terminal:(BOOL)terminal;
@property(nonatomic,copy) void (^onStop)(void);
@end
