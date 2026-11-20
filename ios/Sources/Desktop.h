// SPDX-License-Identifier: GPL-3.0-or-later
#import <UIKit/UIKit.h>
#include "../Session/DecoderChoice.hpp"
@interface FHDesktopController : UIViewController
- (instancetype)initWithFrames:(std::shared_ptr<flatheadset::Frames>)frames decoderChoice:(std::shared_ptr<flatheadset::DecoderChoice>)choice;
- (void)showSessionStatus:(NSString*)status terminal:(BOOL)terminal;
@property(nonatomic,copy) void (^onStop)(void);
@end
