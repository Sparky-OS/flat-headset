// SPDX-License-Identifier: GPL-3.0-or-later
#import <UIKit/UIKit.h>
#include "../Session/DecoderChoice.hpp"
#include "../Viewer/Viewer.hpp"
@interface FHDesktopController : UIViewController
- (instancetype)initWithFrames:(std::shared_ptr<flatheadset::Frames>)frames decoderChoice:(std::shared_ptr<flatheadset::DecoderChoice>)choice viewer:(std::shared_ptr<const flatheadset::Viewer>)viewer;
- (void)showSessionStatus:(NSString*)status terminal:(BOOL)terminal;
@property(nonatomic,copy) void (^onStop)(void);
@end
