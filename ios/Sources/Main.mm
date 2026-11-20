// SPDX-License-Identifier: GPL-3.0-or-later
#import <UIKit/UIKit.h>
#import <MetalKit/MetalKit.h>
#include "Pattern.hpp"
#include <vector>

@interface FHViewController : UIViewController <MTKViewDelegate>
@end

@implementation FHViewController {
    MTKView *_metalView;
    id<MTLCommandQueue> _queue;
    id<MTLRenderPipelineState> _pipeline;
    id<MTLTexture> _pattern;
    UILabel *_failure;
}

- (UIInterfaceOrientationMask)supportedInterfaceOrientations { return UIInterfaceOrientationMaskLandscape; }
- (BOOL)prefersStatusBarHidden { return YES; }
- (BOOL)prefersHomeIndicatorAutoHidden { return YES; }

- (void)failWithKey:(NSString *)key {
    _metalView.paused = YES;
    _metalView.delegate = nil;
    [_metalView removeFromSuperview];
    if (!_failure) {
        _failure = [[UILabel alloc] initWithFrame:self.view.bounds];
        _failure.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
        _failure.textColor = UIColor.whiteColor;
        _failure.textAlignment = NSTextAlignmentCenter;
        _failure.numberOfLines = 0;
        [self.view addSubview:_failure];
    }
    _failure.text = NSLocalizedString(key, nil);
}

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = UIColor.blackColor;
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (!device) { [self failWithKey:@"metal_unavailable"]; return; }
    _metalView = [[MTKView alloc] initWithFrame:self.view.bounds device:device];
    _metalView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    _metalView.colorPixelFormat = MTLPixelFormatBGRA8Unorm;
    _metalView.autoResizeDrawable = NO;
    _metalView.paused = YES;
    _metalView.enableSetNeedsDisplay = YES;
    _metalView.delegate = self;
    [self.view addSubview:_metalView];
    _queue = [device newCommandQueue];
    // Fullscreen triangle. Exact texel read: no resampling, lens correction or eye stretch.
    NSError *error = nil;
    id<MTLLibrary> library = [device newDefaultLibrary];
    MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
    descriptor.vertexFunction = [library newFunctionWithName:@"fullPanel"];
    descriptor.fragmentFunction = [library newFunctionWithName:@"patternPixel"];
    descriptor.colorAttachments[0].pixelFormat = _metalView.colorPixelFormat;
    if (!_queue || !descriptor.vertexFunction || !descriptor.fragmentFunction) {
        NSLog(@"Flat Headset renderer initialization failed: %@", error);
        [self failWithKey:@"renderer_failed"];
        return;
    }
    _pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!_pipeline) {
        NSLog(@"Flat Headset pipeline failed: %@", error);
        [self failWithKey:@"renderer_failed"];
    }
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    if (_failure || !self.view.window || self.view.bounds.size.width < self.view.bounds.size.height) { return; }
    // Screen nativeBounds are physical pixels, unlike UIKit points and scale=3 backing stores.
    CGRect native = self.view.window.screen.nativeBounds;
    CGSize pixels = CGSizeMake(MAX(native.size.width, native.size.height), MIN(native.size.width, native.size.height));
    if (!CGSizeEqualToSize(_metalView.drawableSize, pixels) || !_pattern) {
        _metalView.drawableSize = pixels;
        [self rebuildPattern:pixels];
    }
    [_metalView setNeedsDisplay];
}

- (void)rebuildPattern:(CGSize)size {
    if (_failure) { return; }
    if (size.width < 2 || size.height < 1 || size.width > 16384 || size.height > 16384) {
        [self failWithKey:@"renderer_failed"]; return;
    }
    const auto width = static_cast<uint32_t>(size.width);
    const auto height = static_cast<uint32_t>(size.height);
    const size_t stride = static_cast<size_t>(width) * 4;
    std::vector<uint8_t> pixels(stride * height);
    if (!flatheadset::makePattern(width, height, pixels.data(), stride, pixels.size())) {
        [self failWithKey:@"renderer_failed"]; return;
    }
    MTLTextureDescriptor *descriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
        width:width height:height mipmapped:NO];
    descriptor.usage = MTLTextureUsageShaderRead;
    descriptor.storageMode = MTLStorageModeShared;
    _pattern = [_metalView.device newTextureWithDescriptor:descriptor];
    if (!_pattern) { [self failWithKey:@"renderer_failed"]; return; }
    [_pattern replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:pixels.data() bytesPerRow:stride];
}

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size {
    // Layout sets the final physical size; avoid generating an intermediate points-sized texture.
}

- (void)drawInMTKView:(MTKView *)view {
    if (!_pipeline || !_pattern || _failure) { return; }
    MTLRenderPassDescriptor *pass = view.currentRenderPassDescriptor;
    id<CAMetalDrawable> drawable = view.currentDrawable;
    if (!pass || !drawable) { return; } // iOS can temporarily withhold a drawable while backgrounded.
    if (drawable.texture.width != _pattern.width || drawable.texture.height != _pattern.height) {
        [self failWithKey:@"renderer_failed"]; return;
    }
    id<MTLCommandBuffer> command = [_queue commandBuffer];
    id<MTLRenderCommandEncoder> encoder = [command renderCommandEncoderWithDescriptor:pass];
    if (!command || !encoder) { [self failWithKey:@"render_failed"]; return; }
    [encoder setRenderPipelineState:_pipeline];
    [encoder setFragmentTexture:_pattern atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    [command presentDrawable:drawable];
    __weak FHViewController *controller = self;
    [command addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        if (completed.status == MTLCommandBufferStatusError) {
            NSLog(@"Flat Headset Metal command failed: %@", completed.error);
            dispatch_async(dispatch_get_main_queue(), ^{ [controller failWithKey:@"render_failed"]; });
        }
    }];
    [command commit];
}
@end

@interface FHAppDelegate : UIResponder <UIApplicationDelegate>
@property(nonatomic, strong) UIWindow *window;
@end
@implementation FHAppDelegate
- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)options {
    self.window = [[UIWindow alloc] initWithFrame:UIScreen.mainScreen.bounds];
    self.window.rootViewController = [[FHViewController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}
- (void)applicationDidBecomeActive:(UIApplication *)application {
    [self.window.rootViewController.view setNeedsLayout];
}
@end

int main(int argc, char *argv[]) {
    @autoreleasepool { return UIApplicationMain(argc, argv, nil, NSStringFromClass(FHAppDelegate.class)); }
}
