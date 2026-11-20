// SPDX-License-Identifier: GPL-3.0-or-later
// Authored syntax-test doubles, inferred from our call sites. NOT an Apple SDK.
#pragma once
#ifndef FH_OBJC_SYNTAX_ONLY
#error These test doubles must never enter a product build
#endif
#include <cstddef>
#include <cstdint>
using NSUInteger = unsigned long;
using NSInteger = long;
using CGFloat = double;
using BOOL = bool;
#define YES true
#define NO false
#define nil nullptr
#define MAX(a,b) ((a)>(b)?(a):(b))
#define MIN(a,b) ((a)<(b)?(a):(b))
struct CGPoint { CGFloat x, y; };
struct CGSize { CGFloat width, height; };
struct CGRect { CGPoint origin; CGSize size; };
struct NSRange { NSUInteger location, length; };
constexpr NSUInteger NSNotFound = ~NSUInteger(0);
CGRect CGRectMake(CGFloat, CGFloat, CGFloat, CGFloat);
CGSize CGSizeMake(CGFloat, CGFloat);
@class UITextField, NSString, NSData, NSCharacterSet, NSIndexSet, UIWindow, UINavigationController, UINavigationItem;
@protocol NSObject
- (BOOL)isEqual:(id)other;
@end
__attribute__((objc_root_class))
@interface NSObject <NSObject>
+ (instancetype)alloc;
+ (instancetype)new;
- (instancetype)init;
- (id)copy;
- (id)mutableCopy;
@end
@interface NSString : NSObject
@property(readonly) NSUInteger length;
@property(readonly) const char* UTF8String;
- (instancetype)initWithBytes:(const void*)bytes length:(NSUInteger)length encoding:(NSUInteger)encoding;
- (instancetype)initWithData:(NSData*)data encoding:(NSUInteger)encoding;
- (BOOL)isEqualToString:(NSString*)string;
- (NSRange)rangeOfCharacterFromSet:(NSCharacterSet*)set;
+ (instancetype)stringWithFormat:(NSString*)format, ...;
@end
constexpr NSUInteger NSUTF8StringEncoding = 1;
NSString* NSLocalizedString(NSString*, NSString*);
@interface NSData : NSObject
+ (instancetype)data;
+ (instancetype)dataWithBytes:(const void*)bytes length:(NSUInteger)length;
@property(readonly) NSUInteger length;
@property(readonly) const void* bytes;
@end
@interface NSNumber : NSObject
+ (instancetype)numberWithBool:(BOOL)value;
@end
@interface NSError : NSObject
@end
// Only enough of fast enumeration to let Clang check the caller's loop.
struct NSFastEnumerationState;
@protocol NSFastEnumeration
- (NSUInteger)countByEnumeratingWithState:(NSFastEnumerationState*)state objects:(id __unsafe_unretained*)objects count:(NSUInteger)count;
@end
@interface NSArray<ObjectType> : NSObject <NSFastEnumeration>
+ (instancetype)array;
+ (instancetype)arrayWithObjects:(const id[])objects count:(NSUInteger)count;
@property(readonly) NSUInteger count;
@property(readonly) ObjectType firstObject;
- (ObjectType)objectAtIndexedSubscript:(NSUInteger)index;
@end
@interface NSMutableArray<ObjectType> : NSArray<ObjectType>
- (void)addObject:(ObjectType)object;
- (void)removeObject:(ObjectType)object;
- (void)removeAllObjects;
- (void)removeObjectsAtIndexes:(NSIndexSet*)indexes;
- (NSIndexSet*)indexesOfObjectsPassingTest:(BOOL (^)(ObjectType, NSUInteger, BOOL*))predicate;
@end
@interface NSDictionary<KeyType, ObjectType> : NSObject
+ (instancetype)dictionaryWithObjects:(const id[])objects forKeys:(const id[])keys count:(NSUInteger)count;
- (ObjectType)objectForKeyedSubscript:(KeyType)key;
@end
@interface NSMutableDictionary<KeyType, ObjectType> : NSDictionary<KeyType, ObjectType>
- (void)addEntriesFromDictionary:(NSDictionary*)other;
@end
@interface NSIndexSet : NSObject
@end
@interface NSIndexPath : NSObject
@property(readonly) NSInteger row;
@end
@interface NSCharacterSet : NSObject
@property(class,readonly) NSCharacterSet* controlCharacterSet;
@end
@interface NSNotificationCenter : NSObject
+ (instancetype)defaultCenter;
- (void)addObserver:(id)observer selector:(SEL)selector name:(NSString*)name object:(id)object;
- (void)removeObserver:(id)observer;
@end
@protocol NSNetServiceBrowserDelegate @end
@protocol NSNetServiceDelegate @end
@interface NSNetService : NSObject
@property(weak) id<NSNetServiceDelegate> delegate;
@property(readonly) NSData* TXTRecordData;
@property(readonly) NSArray<NSData*>* addresses;
@property(readonly) NSInteger port;
+ (NSDictionary*)dictionaryFromTXTRecordData:(NSData*)data;
- (void)stop;
- (void)resolveWithTimeout:(double)timeout;
@end
@interface NSNetServiceBrowser : NSObject
@property(weak) id<NSNetServiceBrowserDelegate> delegate;
- (void)stop;
- (void)searchForServicesOfType:(NSString*)type inDomain:(NSString*)domain;
@end
// Values deliberately carry no API/ABI promise.
enum { UIViewAutoresizingFlexibleWidth=1, UIViewAutoresizingFlexibleHeight=2,
    NSTextAlignmentCenter, UIButtonTypeSystem, UIControlStateNormal, UIControlEventTouchUpInside,
    UIBarButtonSystemItemDone, UIBarButtonItemStylePlain, UIApplicationStateActive,
    UITableViewRowActionStyleDestructive, UITableViewCellStyleSubtitle,
    UIAlertControllerStyleAlert, UIAlertActionStyleCancel, UIAlertActionStyleDefault };
using UIInterfaceOrientationMask = NSUInteger;
constexpr UIInterfaceOrientationMask UIInterfaceOrientationMaskLandscape = 1;
@interface UIColor : NSObject
@property(class,readonly) UIColor* blackColor;
@property(class,readonly) UIColor* whiteColor;
+ (UIColor*)colorWithWhite:(CGFloat)white alpha:(CGFloat)alpha;
@end
@interface UIView : NSObject
- (instancetype)initWithFrame:(CGRect)frame;
@property CGRect frame;
@property CGRect bounds;
@property NSUInteger autoresizingMask;
@property(strong) UIColor* backgroundColor;
@property BOOL hidden;
@property BOOL opaque;
@property(readonly) UIWindow* window;
- (void)addSubview:(UIView*)view;
- (void)setNeedsLayout;
@end
@interface UILabel : UIView
@property(copy) NSString* text;
@property(strong) UIColor* textColor;
@property NSInteger textAlignment;
@property NSInteger numberOfLines;
@end
@interface UIButton : UIView
+ (instancetype)buttonWithType:(NSInteger)type;
@property(readonly) UILabel* titleLabel;
- (void)setTitle:(NSString*)title forState:(NSUInteger)state;
- (void)addTarget:(id)target action:(SEL)selector forControlEvents:(NSUInteger)events;
@end
@interface UIViewController : NSObject
- (instancetype)initWithNibName:(NSString*)name bundle:(id)bundle;
@property(strong) UIView* view;
@property(copy) NSString* title;
@property(readonly) UINavigationController* navigationController;
@property(readonly) UINavigationItem* navigationItem;
@property(readonly) UIViewController* presentedViewController;
@property(readonly) BOOL isBeingDismissed;
@property(readonly) BOOL isMovingFromParentViewController;
- (void)viewDidLoad;
- (void)viewWillAppear:(BOOL)animated;
- (void)viewDidAppear:(BOOL)animated;
- (void)viewWillDisappear:(BOOL)animated;
- (void)viewDidLayoutSubviews;
- (void)dismissViewControllerAnimated:(BOOL)animated completion:(void (^)(void))completion;
- (void)presentViewController:(UIViewController*)controller animated:(BOOL)animated completion:(void (^)(void))completion;
@end
@interface UINavigationController : UIViewController
@property(readonly) UIViewController* topViewController;
- (void)setNavigationBarHidden:(BOOL)hidden animated:(BOOL)animated;
- (UIViewController*)popViewControllerAnimated:(BOOL)animated;
- (void)pushViewController:(UIViewController*)controller animated:(BOOL)animated;
@end
@interface UIBarButtonItem : NSObject
@property(copy) NSString* title;
- (instancetype)initWithBarButtonSystemItem:(NSInteger)item target:(id)target action:(SEL)action;
- (instancetype)initWithTitle:(NSString*)title style:(NSInteger)style target:(id)target action:(SEL)action;
@end
@interface UINavigationItem : NSObject
@property(strong) UIBarButtonItem* leftBarButtonItem;
@property(copy) NSArray<UIBarButtonItem*>* rightBarButtonItems;
@end
@interface UIScreen : NSObject
@property(readonly) CGRect nativeBounds;
@property(readonly) NSInteger maximumFramesPerSecond;
@end
@interface UIWindow : UIView
@property(readonly) UIScreen* screen;
@end
@interface UIApplication : NSObject
@property(class,readonly) UIApplication* sharedApplication;
@property BOOL idleTimerDisabled;
@property(readonly) NSInteger applicationState;
@end
extern NSString* UIApplicationDidEnterBackgroundNotification;
extern NSString* UIApplicationDidBecomeActiveNotification;
@interface UITableView : UIView
@property(strong) UIView* tableHeaderView;
- (void)reloadData;
@end
@interface UITableViewController : UIViewController
@property(strong) UITableView* tableView;
@end
@interface UITableViewCell : UIView
- (instancetype)initWithStyle:(NSInteger)style reuseIdentifier:(NSString*)identifier;
@property(readonly) UILabel* textLabel;
@property(readonly) UILabel* detailTextLabel;
@end
@interface UITableViewRowAction : NSObject
+ (instancetype)rowActionWithStyle:(NSInteger)style title:(NSString*)title handler:(void (^)(UITableViewRowAction*, NSIndexPath*))handler;
@end
@interface UIAlertAction : NSObject
+ (instancetype)actionWithTitle:(NSString*)title style:(NSInteger)style handler:(void (^)(UIAlertAction*))handler;
@end
@interface UITextField : UIView
@property(copy) NSString* text;
@property NSInteger autocorrectionType;
@property NSInteger autocapitalizationType;
@property NSInteger keyboardType;
@end
enum { UITextAutocorrectionTypeNo, UITextAutocapitalizationTypeNone, UIKeyboardTypeURL };
@interface NSUserDefaults : NSObject
@property(class,readonly) NSUserDefaults* standardUserDefaults;
- (NSInteger)integerForKey:(NSString*)key;
- (NSString*)stringForKey:(NSString*)key;
- (void)setInteger:(NSInteger)value forKey:(NSString*)key;
- (void)setObject:(id)value forKey:(NSString*)key;
@end
@interface UIAlertController : UIViewController
@property(readonly) NSArray<UITextField*>* textFields;
- (void)addTextFieldWithConfigurationHandler:(void (^)(UITextField*))handler;
+ (instancetype)alertControllerWithTitle:(NSString*)title message:(NSString*)message preferredStyle:(NSInteger)style;
- (void)addAction:(UIAlertAction*)action;
@end
@interface FHTestSemaphore : NSObject @end
@interface FHTestQueue : NSObject @end
using dispatch_semaphore_t = FHTestSemaphore*;
using dispatch_queue_t = FHTestQueue*;
constexpr uint64_t DISPATCH_TIME_NOW = 0;
constexpr int QOS_CLASS_USER_INITIATED = 1;
dispatch_semaphore_t dispatch_semaphore_create(long);
long dispatch_semaphore_wait(dispatch_semaphore_t, uint64_t);
long dispatch_semaphore_signal(dispatch_semaphore_t);
dispatch_queue_t dispatch_get_main_queue();
dispatch_queue_t dispatch_get_global_queue(long, unsigned long);
void dispatch_async(dispatch_queue_t, void (^)(void));

// Main application/launch entry points, authored from this target's call sites.
@class NSURL;
@protocol UIApplicationDelegate @end
@interface UIResponder : NSObject @end
@interface NSURL : NSObject
@property(readonly) NSString* absoluteString;
@end
@interface UIView (FHMainSyntax)
- (void)removeFromSuperview;
- (void)setNeedsDisplay;
@end
@interface UIViewController (FHMainSyntax)
@property NSInteger modalPresentationStyle;
@end
@interface UINavigationController (FHMainSyntax)
- (instancetype)initWithRootViewController:(UIViewController*)root;
@end
@interface UIScreen (FHMainSyntax)
@property(class,readonly) UIScreen* mainScreen;
@property(readonly) CGRect bounds;
@end
@interface UIWindow (FHMainSyntax)
@property(strong) UIViewController* rootViewController;
- (void)makeKeyAndVisible;
@end
@interface NSObject (FHClassSyntax)
+ (Class)class;
@end
enum { UIModalPresentationFullScreen };
BOOL CGSizeEqualToSize(CGSize, CGSize);
void NSLog(NSString*, ...);
NSString* NSStringFromClass(Class);
int UIApplicationMain(int, char**, NSString*, NSString*);
