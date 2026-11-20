// SPDX-License-Identifier: GPL-3.0-or-later
#import "Desktop.h"
#import <MetalKit/MetalKit.h>
#include <chrono>
@interface FHDesktopController () <MTKViewDelegate>
@end
@implementation FHDesktopController {
    std::shared_ptr<flatheadset::Frames> _frames;
    MTKView* _metal;
    id<MTLCommandQueue> _queue;
    id<MTLRenderPipelineState> _pipeline;
    id<MTLTexture> _texture;
    UILabel* _status;
    dispatch_semaphore_t _slots;
    BOOL _failed, _showedFrame, _oldIdleDisabled, _holdingIdle;
    std::chrono::steady_clock::time_point _started;
}
- (instancetype)initWithFrames:(std::shared_ptr<flatheadset::Frames>)frames {
    self=[super initWithNibName:nil bundle:nil];if(self)_frames=std::move(frames);return self;
}
- (UIInterfaceOrientationMask)supportedInterfaceOrientations {return UIInterfaceOrientationMaskLandscape;}
- (BOOL)prefersStatusBarHidden {return YES;}
- (BOOL)prefersHomeIndicatorAutoHidden {return YES;}
- (void)viewDidLoad {
    [super viewDidLoad];self.view.backgroundColor=UIColor.blackColor;
    _started=std::chrono::steady_clock::now();_slots=dispatch_semaphore_create(2);
    _status=[[UILabel alloc] initWithFrame:self.view.bounds];_status.autoresizingMask=UIViewAutoresizingFlexibleWidth|UIViewAutoresizingFlexibleHeight;
    _status.textColor=UIColor.whiteColor;_status.textAlignment=NSTextAlignmentCenter;_status.numberOfLines=0;
    _status.text=NSLocalizedString(@"waiting_picture",nil);
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();
    _metal=[[MTKView alloc] initWithFrame:self.view.bounds device:device];_metal.autoresizingMask=UIViewAutoresizingFlexibleWidth|UIViewAutoresizingFlexibleHeight;
    _metal.colorPixelFormat=MTLPixelFormatBGRA8Unorm;_metal.autoResizeDrawable=NO;_metal.opaque=YES;
    _metal.delegate=self;_metal.paused=YES;_metal.enableSetNeedsDisplay=NO;
    [self.view addSubview:_metal];[self.view addSubview:_status];
    UIButton* stop=[UIButton buttonWithType:UIButtonTypeSystem];stop.frame=CGRectMake(20,20,120,48);
    stop.backgroundColor=[UIColor colorWithWhite:0 alpha:0.8];[stop setTitle:NSLocalizedString(@"stop_session",nil) forState:UIControlStateNormal];
    [stop addTarget:self action:@selector(stopDesktop) forControlEvents:UIControlEventTouchUpInside];[self.view addSubview:stop];
    if(!device){[self failWithKey:@"metal_unavailable"];return;}
    _queue=[device newCommandQueue];id<MTLLibrary> library=[device newDefaultLibrary];
    MTLRenderPipelineDescriptor* descriptor=[MTLRenderPipelineDescriptor new];descriptor.vertexFunction=[library newFunctionWithName:@"fullPanel"];
    descriptor.fragmentFunction=[library newFunctionWithName:@"desktopPixel"];descriptor.colorAttachments[0].pixelFormat=_metal.colorPixelFormat;
    if(!_queue || !descriptor.vertexFunction || !descriptor.fragmentFunction){[self failWithKey:@"renderer_failed"];return;}
    NSError* error=nil;_pipeline=[device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if(!_pipeline)[self failWithKey:@"renderer_failed"];
}
- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];[self.navigationController setNavigationBarHidden:YES animated:NO];
    if(!_failed){_oldIdleDisabled=UIApplication.sharedApplication.idleTimerDisabled;UIApplication.sharedApplication.idleTimerDisabled=YES;_holdingIdle=YES;}
}
- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];_metal.paused=YES;_frames->close();
    [self releaseIdle];
    [self.navigationController setNavigationBarHidden:NO animated:NO];if(self.onStop)self.onStop();
}
- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];if(_failed || !self.view.window || self.view.bounds.size.width<self.view.bounds.size.height)return;
    UIScreen* screen=self.view.window.screen;CGRect native=screen.nativeBounds;
    _metal.drawableSize=CGSizeMake(MAX(native.size.width,native.size.height),MIN(native.size.width,native.size.height));
    _metal.preferredFramesPerSecond=screen.maximumFramesPerSecond;_metal.paused=NO;
}
- (void)releaseIdle {if(_holdingIdle){UIApplication.sharedApplication.idleTimerDisabled=_oldIdleDisabled;_holdingIdle=NO;}}
- (void)stopDesktop {[self.navigationController popViewControllerAnimated:YES];}
- (void)failWithKey:(NSString*)key {
    if(_failed)return;[self releaseIdle];_failed=YES;_metal.paused=YES;_metal.hidden=YES;_texture=nil;
    _status.hidden=NO;_status.text=NSLocalizedString(key,nil);_frames->close();if(self.onStop)self.onStop();
}
- (void)showSessionStatus:(NSString*)status terminal:(BOOL)terminal {
    if(_failed)return;
    if(terminal){[self releaseIdle];_frames->close();_failed=YES;_metal.paused=YES;_metal.hidden=YES;_texture=nil;_status.hidden=NO;_status.text=status;}
    else if(!_showedFrame)_status.text=NSLocalizedString(@"waiting_picture",nil);
}
- (void)mtkView:(MTKView*)view drawableSizeWillChange:(CGSize)size {}
- (void)drawInMTKView:(MTKView*)view {
    if(_failed || !_pipeline)return;
    if(!_frames->active()){[self releaseIdle];_metal.hidden=YES;_metal.paused=YES;_texture=nil;return;}
    if(!_showedFrame && std::chrono::steady_clock::now()-_started>std::chrono::seconds(45)){[self failWithKey:@"picture_timeout"];return;}
    // Do not consume or queue another frame while both GPU submissions are in use.
    if(dispatch_semaphore_wait(_slots,DISPATCH_TIME_NOW)!=0)return;
    id<CAMetalDrawable> drawable=view.currentDrawable;MTLRenderPassDescriptor* pass=view.currentRenderPassDescriptor;
    if(!drawable || !pass){dispatch_semaphore_signal(_slots);return;}
    if(auto frame=_frames->take()) {
        if(frame->width!=drawable.texture.width || frame->height!=drawable.texture.height){dispatch_semaphore_signal(_slots);[self failWithKey:@"render_failed"];return;}
        MTLTextureDescriptor* descriptor=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:frame->width height:frame->height mipmapped:NO];
        descriptor.usage=MTLTextureUsageShaderRead;descriptor.storageMode=MTLStorageModeShared;
        id<MTLTexture> next=[view.device newTextureWithDescriptor:descriptor];
        if(!next){dispatch_semaphore_signal(_slots);[self failWithKey:@"render_failed"];return;}
        [next replaceRegion:MTLRegionMake2D(0,0,frame->width,frame->height) mipmapLevel:0 withBytes:frame->pixels.data() bytesPerRow:static_cast<NSUInteger>(frame->width)*4];
        _texture=next;
    }
    if(!_texture){dispatch_semaphore_signal(_slots);return;}
    if(_texture.width!=drawable.texture.width || _texture.height!=drawable.texture.height){dispatch_semaphore_signal(_slots);[self failWithKey:@"render_failed"];return;}
    id<MTLCommandBuffer> command=[_queue commandBuffer];id<MTLRenderCommandEncoder> encoder=[command renderCommandEncoderWithDescriptor:pass];
    if(!command || !encoder){dispatch_semaphore_signal(_slots);[self failWithKey:@"render_failed"];return;}
    [encoder setRenderPipelineState:_pipeline];[encoder setFragmentTexture:_texture atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[encoder endEncoding];[command presentDrawable:drawable];
    __weak FHDesktopController* recipient=self;const auto slots=_slots;
    [command addCompletedHandler:^(id<MTLCommandBuffer> completed){
        dispatch_semaphore_signal(slots);
        const BOOL success=completed.status==MTLCommandBufferStatusCompleted;
        dispatch_async(dispatch_get_main_queue(), ^{FHDesktopController* strong=recipient;if(!strong || strong->_failed)return;
            if(!success){[strong failWithKey:@"render_failed"];return;}
            if(strong->_frames->active()){strong->_showedFrame=YES;strong->_status.hidden=YES;}
        });
    }];
    [command commit];
}
@end
