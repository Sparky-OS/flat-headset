// SPDX-License-Identifier: GPL-3.0-or-later
// Authored syntax-test doubles only; no SDK definitions or implementations.
#pragma once
#import <UIKit/UIKit.h>
@protocol MTLBuffer, MTLDevice, MTLCommandQueue, MTLRenderPipelineState, MTLFunction, MTLLibrary, MTLTexture, MTLCommandBuffer, MTLRenderCommandEncoder, CAMetalDrawable;
@class MTKView, MTLRenderPassDescriptor, MTLTextureDescriptor, MTLRenderPipelineDescriptor;
struct MTLRegion { NSUInteger x, y, width, height; };
MTLRegion MTLRegionMake2D(NSUInteger, NSUInteger, NSUInteger, NSUInteger);
enum { MTLPixelFormatRGBA8Unorm, MTLCommandBufferStatusError, MTLPixelFormatBGRA8Unorm, MTLTextureUsageShaderRead, MTLStorageModeShared,
       MTLPrimitiveTypeTriangle, MTLCommandBufferStatusCompleted, MTLResourceStorageModeShared, MTLPrimitiveTypeTriangleStrip, MTLIndexTypeUInt32 };
@protocol MTKViewDelegate
- (void)mtkView:(MTKView*)view drawableSizeWillChange:(CGSize)size;
- (void)drawInMTKView:(MTKView*)view;
@end
@interface MTKView : UIView
- (instancetype)initWithFrame:(CGRect)frame device:(id<MTLDevice>)device;
@property(weak) id<MTKViewDelegate> delegate;
@property NSUInteger colorPixelFormat;
@property BOOL autoResizeDrawable;
@property BOOL paused;
@property BOOL enableSetNeedsDisplay;
@property CGSize drawableSize;
@property NSInteger preferredFramesPerSecond;
@property(readonly) id<MTLDevice> device;
@property(readonly) id<CAMetalDrawable> currentDrawable;
@property(readonly) MTLRenderPassDescriptor* currentRenderPassDescriptor;
@end
@interface MTLRenderPipelineColorAttachmentDescriptor : NSObject
@property NSUInteger pixelFormat;
@end
@interface MTLRenderPipelineDescriptor : NSObject
@property(strong) id<MTLFunction> vertexFunction;
@property(strong) id<MTLFunction> fragmentFunction;
@property(readonly) NSArray<MTLRenderPipelineColorAttachmentDescriptor*>* colorAttachments;
@end
@interface MTLTextureDescriptor : NSObject
+ (instancetype)texture2DDescriptorWithPixelFormat:(NSUInteger)format width:(NSUInteger)width height:(NSUInteger)height mipmapped:(BOOL)mipmapped;
@property NSUInteger usage;
@property NSUInteger storageMode;
@end
@interface MTLRenderPassDescriptor : NSObject @end
@protocol MTLRenderPipelineState <NSObject> @end
@protocol MTLFunction <NSObject> @end
@protocol MTLBuffer <NSObject> @end
struct MTLScissorRect { NSUInteger x,y,width,height; };
@protocol MTLDevice <NSObject>
- (id<MTLBuffer>)newBufferWithBytes:(const void*)bytes length:(NSUInteger)length options:(NSUInteger)options;
- (id<MTLCommandQueue>)newCommandQueue;
- (id<MTLLibrary>)newDefaultLibrary;
- (id<MTLRenderPipelineState>)newRenderPipelineStateWithDescriptor:(MTLRenderPipelineDescriptor*)descriptor error:(NSError**)error;
- (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor*)descriptor;
@end
id<MTLDevice> MTLCreateSystemDefaultDevice();
@protocol MTLLibrary <NSObject>
- (id<MTLFunction>)newFunctionWithName:(NSString*)name;
@end
@protocol MTLTexture <NSObject>
@property(readonly) NSUInteger width;
@property(readonly) NSUInteger height;
- (void)replaceRegion:(MTLRegion)region mipmapLevel:(NSUInteger)level withBytes:(const void*)bytes bytesPerRow:(NSUInteger)stride;
@end
@protocol CAMetalDrawable <NSObject>
@property(readonly) id<MTLTexture> texture;
@end
@protocol MTLCommandQueue <NSObject>
- (id<MTLCommandBuffer>)commandBuffer;
@end
@protocol MTLCommandBuffer <NSObject>
@property(readonly) NSError* error;
@property(readonly) NSUInteger status;
- (id<MTLRenderCommandEncoder>)renderCommandEncoderWithDescriptor:(MTLRenderPassDescriptor*)descriptor;
- (void)presentDrawable:(id<CAMetalDrawable>)drawable;
- (void)addCompletedHandler:(void (^)(id<MTLCommandBuffer>))handler;
- (void)commit;
@end
@protocol MTLRenderCommandEncoder <NSObject>
- (void)setScissorRect:(MTLScissorRect)rect;
- (void)setVertexBuffer:(id<MTLBuffer>)buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index;
- (void)setFragmentBytes:(const void*)bytes length:(NSUInteger)length atIndex:(NSUInteger)index;
- (void)drawIndexedPrimitives:(NSUInteger)type indexCount:(NSUInteger)count indexType:(NSUInteger)indexType indexBuffer:(id<MTLBuffer>)buffer indexBufferOffset:(NSUInteger)offset;
- (void)setRenderPipelineState:(id<MTLRenderPipelineState>)state;
- (void)setFragmentTexture:(id<MTLTexture>)texture atIndex:(NSUInteger)index;
- (void)drawPrimitives:(NSUInteger)type vertexStart:(NSUInteger)start vertexCount:(NSUInteger)count;
- (void)endEncoding;
@end
