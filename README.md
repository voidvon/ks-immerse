# 短视频沉浸模块（Vector / LSPosed）

一个基于 **libxposed API 102** 的 Xposed 模块，只针对**目标应用**（包名见 `Targets.PACKAGE`），
把小视频播放页里挡住画面的各种控件压到 10% 透明度，把暂停时随机乱跑的「搜画面」按钮钉死在右上角，
并把视频从 cover（裁切左右）改成 contain（完整画面）。

已验证版本：`14.7.10.11757`（`versionCode` 见 `Targets.VERIFIED_VERSION`）

---

## 功能

| 开关 | 作用 |
| --- | --- |
| 启用沉浸模式 | 总开关 |
| 目标透明度 | 默认 `0.1`（10%），**只降透明度、不改 visibility**，所以控件依然可点 |
| 右侧整列 | 头像 / 关注 / 点赞 / 评论 / 收藏 / 转发 整条右侧栏 |
| 底部信息区 | 发布者名称 / 说明文案 / 话题标签 |
| 作者声明 | 「作者声明」那块文字 |
| 底部热点条 | 底部那条 `🔥 热点 · xxx`（同一控件也承载「来源：xxx」，两者一起变淡） |
| 右上角红包 / 金币悬浮挂件 | 可拖动的红包金币挂件 |
| 暂停时的「搜画面」固定到右上角 | 见下方「强制覆盖」 |
| 视频完整显示（contain） | 见下方「把 cover 改成 contain」 |
| 沉浸没生效的视频自动划走 | 见下方「判定"没生效"并自动划走」 |
| 出现「广告」标签时快进到最后 0.5 秒 | **实验性，默认关闭**。检测本身已验证可靠（`slide_play_ad_label_container` 在普通视频里是 `GONE`、广告才 `VISIBLE`），但只覆盖带标签的那类广告 |

> 全部处理都走 `alpha`，**从不调用 `setVisibility(GONE)`**，控件位置、点击、拖拽行为都不受影响。

---

## 构建与安装

需要 JDK 21 与 Android SDK（`ANDROID_HOME`）。项目已带 Gradle Wrapper，无需全局 gradle：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`scripts/run-test.sh [秒数]` 是一个现成的「编译 → 安装 → 强停 → 拉起 → 抓日志」脚本：

```bash
./scripts/run-test.sh 20      # 日志落在 /tmp/ks-immerse.log
```

装好后在 **Vector 管理器**里启用模块，作用域勾选目标应用，然后强行停止目标应用再打开。
改任何设置后都要重启目标应用，控件才会重新创建。

---

## 核心难点与实现

### 1. 资源被 AndResGuard 混淆，但 id 名字没变

目标应用的 `res/layout/x.xml` 被改名成 `r/ar/a.xml`，但 `resources.arsc` 里的 **id 名字是完整的**。
更麻烦的是真正的 UI 资源在**第二张资源表** `assets/*_res/core_res` 里（11982 个 id / 3947 个布局）。

因此策略是：

* **数值 id 为主**——id 是编译进布局的，直接写死数值 100% 可靠；
* **`Resources.getIdentifier` 按名字解析为辅**——用于未来版本自愈（见 `ImmerseConfig.resolveNames`）。

### 2. 首页信息流 ≠ 视频详情页

两套完全不同的布局，早期按详情页的 id 去改首页自然毫无效果：

| | 首页信息流（Milano / slide_v2） | 视频详情页 |
| --- | --- | --- |
| 右侧栏 | `group_right_action_bar_root_layout` | `global_table_layout` / `side_interactive_container` |
| 底部信息 | `group_information_root_layout` | `global_caption_layout` |

### 3. 祖先去重，避免透明度相乘

如果父子都被命中，`0.1 × 0.1 = 0.01` 就几乎看不见了。
`ImmerseConfig.applyTo()` 里用 `hasTargetAncestor()` 跳过「祖先已经是目标」的子控件。

### 4. 红包金币挂件：没有 id，只能按类名

它由 Tachikoma（自研跨端渲染）绘制，**整个视图树 id 全是 `0xffffffff`**：

```
FrameLayout(270x270) > UnionView > TKViewContainer > TKYogaLayout > TKAndroidCanvasView
```

所以按类名匹配 `com.<app>.growth.pendant.coin.core.kds.unionwidget.UnionView`。
选这一层是因为它既覆盖整个挂件，类名又足够专属（其他 Tachikoma 元素用的是 `TKViewContainer`）。

### 5. 把 cover 改成 contain（视频不再被裁掉左右）

目标应用把真正的渲染层 `SurfaceView` 按 **cover** 撑大后塞进容器里：

```
FrameLayout id=texture_view_frame      1080x2157   <- 容器
  <Player>KitView    id=slide_playerkit_view
    PlayerKitContentFrame              1213x2157
      SurfaceView                      1213x2157   <- 比容器宽 133px，两侧被容器裁掉
```

做法就是用户说的那句话：**量出 x 轴被裁掉多少，再等比缩回去。**
`SurfaceView` 的宽高比就等于视频比例（播放器是把它撑到 cover 尺寸，
裁切由外层容器负责），所以

```
scale = min(容器宽 / SurfaceView 宽, 容器高 / SurfaceView 高)
目标   = (SurfaceView 宽 * scale, SurfaceView 高 * scale)
```

`1213x2157` → `scale = 1080/1213 = 0.8903` → `1080x1920`，正好是完整画面。

#### 但比例不能从 View 尺寸猜 —— 要直接问播放器

一开始是按上面那套纯几何写的，结果**有些视频不生效**。原因是几何来源都不可靠：

* `PlayerKitContentFrame` **有时候正好等于容器尺寸**（此时 `vAspect == cAspect`，
  看起来"不需要裁切"），于是被误判成不用处理；
* `SurfaceView` 自己的尺寸会被**我们上一次写进去的值**污染 ——
  ViewPager 会把同一个 `SurfaceView` 实例复用给下一个视频，
  于是 16:9 的视频被塞进上一轮留下的 `1080x1920` 里 → **纵向拉伸**，
  这就是"比例错误 / 看起来很生硬"的来源。

所以现在**直接问播放器要真实宽高**（这两个接口没有被混淆，
在播放器的两个核心接口 `IMediaPlayer` / `IMediaPlayerApi` 上都有）：

```java
player.getVideoWidth()   // -> I
player.getVideoHeight()  // -> I
```

拿到 `vAspect` 之后按"等比缩到刚好放得下"算目标：

```kotlin
if (vAspect > cAspect) {        // 视频比容器宽 → cover 裁的是左右
    target = cW to round(cW / vAspect)
} else {                        // 视频比容器高 → cover 裁的是上下
    target = round(cH * vAspect) to cH
}
```

顺带修掉了旧版的两个毛病：视频比容器**高**的情况以前是"原样不动"（于是上下被裁），
现在会正确留出上下黑边。

**退路：** 拿不到播放器尺寸时（广告页 `findPlayer` 返回 null、或还没 prepare），
才回退到上面那套纯几何测量；并且"装得下就什么都不做"，
所以不会和自己上一轮写进去的尺寸互相打架。

日志每个视频一条（按视频尺寸 + 容器去重，不能只按容器去重 —— 容器会被复用）：

```
CONTAIN 1213x2157 -> 1080x1920 container=1080x2157 video=576x1024(0.5625) cAspect=0.5007 cover裁掉 x=133px y=0px
CONTAIN 1080x607  -> 1080x608  container=1080x809  video=1024x576(1.7778) cAspect=1.3350 cover裁掉 x=358px y=0px
```

真机核对过：9:16 的视频在 `1080x2157` 容器里留出上下各 118px 黑边
（`(2157-1920)/2`），和算出来的完全一致；4:3 的视频黑边明显变大，也没有拉伸。

关掉这个开关时会把尺寸交回播放器（`LayoutParams` 设回 `MATCH_PARENT`），恢复原生 cover。

### 6. 「搜画面」按钮：必须每帧强制覆盖

这是最有意思的一个。链路：

```
SearchPicBubbleItemView          (无 id)
  SearchPicBubbleElement         (无 id)
    RelativeLayout  id=search_picture_entrance_v1   ← 药丸容器 351x84
      LinearLayout  id=slide_entrance_layout_v1     ← 药丸本体
        TextView    id=guiding_bubble_text  "搜画面"
```

**目标应用给每个视频随机一个位置**，设置一次会被它自己改回去。所以：

* 在 `View#onAttachedToWindow` 命中后启动一个 `postOnAnimation` 自调度循环，
  **每帧**重算 translation 把控件拉回右上角 —— 相当于强制覆盖；
* 用 `translationX/Y` 而不是改 `LayoutParams`：不受父容器类型限制、不触发重新布局，
  而且 **translation 同样作用于命中测试**，所以点击热区会跟着视觉一起走，不会留下「隐形按钮」；
* 移动的是**最外层**的 `SearchPicBubble*` 容器而不是内层 `RelativeLayout` ——
  真正响应点击的是外层，只挪内层的话视觉走了、点击区还留在原地（这正是误触的来源）。

「可见区域」的判定也很关键：一开始按**屏幕**右上角摆，视频有黑边时会被父容器**裁剪掉**，
表现就是「按钮消失了」。正确做法是拿真实视频画面矩形当参照：

```kotlin
val VIDEO_REF_IDS = intArrayOf(
    ID_TEXTURE_VIEW_FRAME,      // 0x7f091dff  真正的画面矩形（黑边时只覆盖画面）
    ID_SLIDE_PLAYERKIT_VIEW,    // 0x7f091bca
    ID_PLAYER,                  // 0x7f0915db
)
```

纵向取 `max(画面顶 + 12dp, 屏幕顶 + 96dp)`：全屏视频避开顶部导航栏，黑边视频则贴画面自身顶部。

---

### 7. 判定「没生效」并自动划走

广告页用的不是信息流那套右侧栏 / 底部信息区控件，透明度压不上去，沉浸效果出不来。
与其去猜「什么样算广告」，不如直接判断**这一页到底有没有被压暗**。

**关键约束：每个视频只判定一次。** 不是「持续观察、发现不对就划」——
那样会一边看一边反复判定，用户拉开评论区时右侧栏被盖住，
判据立刻变成「没生效」，视频就被划走了。正确做法是**等这一页停稳，判定一次，然后闭嘴**：

```
翻到新的一页 → 等画面停稳 1.8s → 判定一次 → 记下"这页判定过了" → 之后不再判定
```

判定结果每页打一行，便于核对「是不是真的只判定了一次」：

```
UNMATCHED 状态：换了一页
JUDGE #1：这一页沉浸已生效，保持不动 (y=0 h=2157)
JUDGE #4：这一页沉浸没生效，划走 (y=0 h=2157)
```

「这一页」用**可见视频画面的实例**（`System.identityHashCode`）来标识：
ViewPager 翻页时画面实例会换，而拉开评论区不会，所以两者天然区分得开。

这条路踩了三个坑，都记在代码注释里：

**坑 1：回收页会骗过可见性判断。**
ViewPager 同时挂着前后各一页，那些回收页的 `isShown()` 依然是 `true`、
alpha 也还是我们压过的 `0.1`。只看 `isShown()` 的话，一个广告页会因为它**邻居**被压暗而
被判定成「生效」。所以判据必须落到屏幕坐标：

```kotlin
// 控件至少一半落在屏幕里，才算"这一页的"
val onScreen = minOf(loc[1] + v.height, screenH) - maxOf(loc[1], 0)
if (onScreen < v.height * 0.5f) continue
```

> 中途试过按「页根容器」（`SlidePlayVerticalRelativeLayout`）收敛范围，
> 结果那个容器是**跨页共享**的祖先，反而把邻居页的目标控件又收了进来 —— 已回退。

**坑 2：不能用播放器是否存在当守卫。**
广告页上 `findPlayer()` 拿不到播放器（返回 `null`），如果拿它当「是不是视频页」的守卫，
广告页就会被当成非视频页**永远跳过**。改成用视频画面矩形判断：

```kotlin
if (frame == null || frame.height < screenH * 0.2f) return   // 不是整页视频
```

**坑 3：评论区要单独挡掉。**
评论区开着时右侧栏被盖住，「屏幕上没有生效的目标控件」会把评论区误判成广告页。
判据是 `comment_panel_container`（`0x7f0906c1`）在不在。

> ⚠️ **只能认这一个 id。** 实测（`uiautomator dump`）：评论区**关着**时
> `comment_panel_container` 不在树里，但 `comment_container` /
> `comment_secondary_panel_container` **一直都在**（面板是预先 inflate 好的）。
> 拿后者当判据会一直「以为评论区开着」，自动划走直接失效 —— 这个 bug 我写出来又踩了一次。

另外两处防误判：

* 画面**几何**（纵向位置 + 高度）有任何变化就重新计时 —— 翻页动画和拉开评论区都会改几何，
  所以判定永远发生在「画面已经静止」的时刻；
* 必须先见过至少一个「生效」的视频页，确认自己确实在信息流里。

划走用合成 `MotionEvent` 注入（`DOWN` → 10 步 `MOVE` → `UP`，事件时间按 16ms 真实递进）——
时间不递进的话宿主的 ViewPager 算不出滑动速度，翻不了页。

实测（连续翻 5 页）：`JUDGE` 正好 5 行，其中只有 1 页是「没生效，划走」，
其余 4 页「已生效，保持不动」；拉开评论区后 0 次划走。

---

## 代码结构

```
app/src/main/java/com/yytest/ksimmerse/
  Targets.kt         所有 id / 类名常量，附逆向结论注释
  ImmerseConfig.kt   配置读写、按 id/类名判命中、祖先去重、透明度应用
  ImmerseModule.kt   全部 hook：onAttachedToWindow / setAlpha / inflate / onCreate / onResume
  SettingsActivity.kt 设置界面
  ConfigProvider.kt  只读 ContentProvider —— 把设置实时送给宿主进程
app/src/main/resources/META-INF/xposed/
  module.prop        minApiVersion / targetApiVersion / staticScope / autoHotReload
  java_init.list     入口类
```

### 关键 hook

| 方法 | 用途 |
| --- | --- |
| `View#onAttachedToWindow` | **主力**。所有窗口（含悬浮窗）都会走，发现目标控件并立刻降透明度 |
| `View#setAlpha` | 兜底。拦住 `alpha > 配置值` 的调用，防止目标应用把透明度改回去 |
| `LayoutInflater#inflate` | 兜底。新 inflate 出来的子树立即扫一遍 |
| `Activity#onCreate` | 按名字解析 id（自愈） |
| `Activity#onResume` | 调试用视图树 dump |

> `ViewRootImpl#setView` 虽然能挂上 hook，但调用点被内联了、**永远不会触发**，不要依赖它。

---

## 逆向资料（`_recon/`）

> 📁 **该目录已被 `.gitignore` 忽略，只存在于本地**（约 160MB，含第三方 APK）。
> 需要版本化的是结论本身——也就是 `README.md` 和 `Targets.kt` 的注释。

加新功能时不用重新逆向，这些现成产物都在：

| 文件 | 内容 |
| --- | --- |
| `base.apk` | 14.7.10.11757 原始安装包（版本锁定的参考底本） |
| `core-res-dump.txt` | `assets/*_res/core_res` 第二资源表全量 dump（163k 行，含全部 id 名） |
| `idmap.json` | 74140 条 `0x7f...` → `id/名字` 映射 |
| `all-ids.txt` | 全部 id 名字列表（按名字搜控件用） |
| `resx/r/ar/*.xml` | 反混淆后的布局文件 |
| `resolve.py` | 把 `resx` 里布局中的 id 数值还原成名字 |

```bash
# 例：按名字找某个控件的数值 id
grep -E 'resource 0x[0-9a-f]+ id/你的控件名$' _recon/core-res-dump.txt

# 例：把布局里的 id 还原成名字
cd _recon && python3 resolve.py r/ar/qz.xml
```

需要重新解 dex 时（找类名）：

```bash
cd _recon && unzip -o base.apk 'classes*.dex' -d dex/
```

---

## 已知未覆盖

* 广告 / 直播视频用的是另一套底部浮层（`@xxx +关注` / `新鲜采摘玉米好好吃` 那块），暂未处理
  —— 这类页面会被「沉浸没生效的视频自动划走」直接跳掉；
* 只按 `14.7.10.11757` 适配。换版本若控件 id 变化，改 `Targets.kt` 即可
  （开着 `debug_dump_tree` 能看到带 id、坐标、父类、LayoutParams 的完整视图树）。

### 设置怎么送到宿主进程（以及为什么不能用 remote preferences）

本机实测 `getRemotePreferences("ks_immerse")` 返回的对象 **`keys=[]`**（空），
所以宿主进程里所有开关都落在代码默认值上 —— 设置界面改了不生效。

模块侧代码是对的，问题出在 Vector 的 remote preferences 通道没被填充。
已经排除过的原因：

* `AndroidManifest.xml` 里已经声明
  `<meta-data android:name="xposedsharedprefs" android:value="true"/>`；
* 重装 APK、`vector-cli modules disable/enable` 重新加载模块都没用；
* 模块的 `shared_prefs/ks_immerse.xml` 确实存在、内容也正确；
* 想让 root 把它改成 world-readable 也不行 —— SELinux 不允许 `chmod` 应用私有目录，
  而 DAC 上 `/data/data/<pkg>` 是 `drwx------`，宿主进程（另一个 uid）本来就读不到。

排查时可以用这条日志确认：

```
KSImmerse: prefs group=ks_immerse null=false keys=[]      ← 官方通道拿到的是空对象
```

**所以自己走了一条：模块里声明一个只读 `ContentProvider`，宿主进程查询它、
并注册 `ContentObserver` 监听变化。**

```
模块进程                                       宿主进程
────────                                       ────────
SettingsActivity.persist()
  └─ 写 SharedPreferences
  └─ contentResolver.notifyChange(URI)
                    │
                    ▼
        ConfigProvider.query()  ◄──── Application#onCreate 时查一次
        （读自己的 SharedPreferences）      registerContentObserver(URI)
                                              └─ onChange → 重新载入 → reapplyAll
```

几个关键点：

* **`reapplyAll` 不能省。** 配置变了以后，屏幕上**已经渲染出来**的控件不会自己更新，
  必须主动遍历视图树重新刷一遍 alpha，否则看起来就是「改了没反应」。
  不能复用 `applyToTree`：那个用 `findViewById` 只找每个 id 的第一个，
  而 ViewPager 同时挂着 3 页；而且关掉总开关时 `isTarget` 返回 false，
  `applyToTree` 直接不干活，界面会永远停在压暗状态。
* **只给主进程装通道。** `Application#onCreate` 在每个进程都会跑，
  给 push / messagesdk 也装一份的话，改一次设置要触发四五次重载。

#### ⚠️ 装了新 APK 之后要手动打开一次模块界面

Android 把刚安装、还没启动过的应用标记为 **stopped**，
ColorOS 的关联启动管控会直接拦掉「宿主去启动模块」这一步：

```
W/OplusAppStartupManager: prevent start com.yytest.ksimmerse,
    cmp ComponentInfo{com.yytest.ksimmerse/...ConfigProvider}
    by contentprovider <宿主包名> callingUid 10527, scenePriority = 0
E/ActivityThread: Failed to find provider info for com.yytest.ksimmerse.config
```

表现就是 `query` 返回 `null`、配置读不到。**手动打开一次模块界面**之后这个标记就清掉了，
之后一直正常。为了不让用户踩这个坑，读不到时不会报错，
而是每 3 秒静默重试一次（`retryConfigIfNeeded`），所以就算忘了，
下次从模块界面切回宿主也会自动补上：

```
KSImmerse: 设置 Provider 暂无数据（启动），沿用默认值，稍后重试
KSImmerse: config reloaded from provider（重试）: enabled=true alpha=0.1 ...
```

#### 实测（真机）

```
# 改设置时，宿主进程立刻收到回调
config reloaded from provider（设置变化）: enabled=true alpha=0.49 ...
# 而且已经渲染出来的控件当场跟着变
MATCH: ... [0x7f090baa a=0.49 ...] [0x7f090bb4 a=0.49 ...]
```

---

## 调试

`ImmerseConfig.KEY_DEBUG_DUMP`（默认关）打开后会：

* 打印每个挂载且有尺寸的控件的 `id / 类名 / 坐标 / 尺寸 / alpha / visibility`；
* 在 `Activity#onResume` 后按 2.5s 间隔 dump 12 轮完整视图树（含文本、父类、LayoutParams）。

> ⚠️ dump 一轮就是 900 行，**会把 logcat 缓冲区冲爆导致丢日志**。
> 排查「hook 到底有没有跑」时务必先把它关掉，否则会得到完全错误的结论。
