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
    PlayerKitContentFrame              1213x2157   <- 播放器按视频真实比例定尺寸
      SurfaceView                      1213x2157   <- 比容器宽 133px，两侧被容器裁掉
```

**关键点：`SurfaceView` 的尺寸不等于视频比例**——它是被 cover 撑大的。
真正可靠的原生比例来源是它的**父层 `PlayerKitContentFrame`**：播放器按视频真实比例给它定尺寸，
而且不会因为我们改子 View 而变化。

于是把 `SurfaceView` 的 `LayoutParams` 设成"按父层比例在容器内放得下"的尺寸并居中：

```kotlin
val sp = surface.parent as View                 // PlayerKitContentFrame = 视频原生尺寸
val vAspect = sp.width / sp.height.toDouble()
val cAspect = container.width / container.height.toDouble()
if (vAspect > cAspect) {                        // 视频比容器宽 => 左右被裁切
    target = container.width to round(container.width / vAspect)
} else {
    target = sp.width to sp.height              // 原生尺寸，等价 MATCH_PARENT
}
```

#### 两个必须遵守的约束

1. **比例来源只能用父层，绝不能用 `SurfaceView` 自己。**
   `SurfaceView` 一旦被我们设过显式尺寸，这个值会**跟着 SurfaceView 实例复用给下一个视频**
   （ViewPager 复用页面），于是 16:9 的视频被塞进上一轮留下的 `1080x1920` 里 → **纵向拉伸**。
   父层尺寸不受影响，所以按父层重算天然是无状态、幂等的。

2. **只处理"视频比容器宽"（左右裁切）。**
   视频比容器**高**的情况（图文帖、小窗预览）是目标应用自己的版式，
   硬按 contain 缩会把画面变成一条窄柱，所以那种情况目标就是原生尺寸、原样不动。

改动只在尺寸变化时执行一次，稳定后不再触发（轮询 150ms 兜底）。

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
与其去猜「什么样算广告」，不如直接判断**这一页到底有没有被压暗**：

```
屏幕上存在任意一个目标控件，且 alpha <= 设定值   →  这一页生效
否则持续 2 秒                                    →  注入上滑手势翻到下一个
```

这条路踩了两个坑，都记在代码注释里：

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

另外两处防误判：

* 翻页动画中间那几帧本来就没有目标控件，所以要求画面**停稳**（纵向位置 2 帧内不变）才开始计时；
* 必须先见过至少一个「生效」的视频页，确认自己确实在信息流里。

划走用合成 `MotionEvent` 注入（`DOWN` → 10 步 `MOVE` → `UP`，事件时间按 16ms 真实递进）——
时间不递进的话宿主的 ViewPager 算不出滑动速度，翻不了页。

---

## 代码结构

```
app/src/main/java/com/yytest/ksimmerse/
  Targets.kt         所有 id / 类名常量，附逆向结论注释
  ImmerseConfig.kt   配置读写、按 id/类名判命中、祖先去重、透明度应用
  ImmerseModule.kt   全部 hook：onAttachedToWindow / setAlpha / inflate / onCreate / onResume
  SettingsActivity.kt 设置界面
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

### 设置界面读不到的问题

本机实测 `getRemotePreferences("ks_immerse")` 返回的对象 **`keys=[]`**（空），
所以宿主进程里所有开关都落在代码默认值上 —— 设置界面改了不生效。

模块侧代码是对的，问题出在 Vector 的 remote preferences 镜像没被填充
（`/data/misc/<uuid>/prefs/` 下没有本模块的文件，即使已在 `AndroidManifest.xml` 里声明
`<meta-data android:name="xposedsharedprefs" android:value="true"/>`、重装 APK、
并用 `vector-cli modules disable/enable` 重新加载过）。

排查时可以用这条日志确认当前进程实际拿到的值：

```
KSImmerse: prefs group=ks_immerse null=false keys=[]
KSImmerse: hooks installed, ... enabled=true alpha=0.1 ... autoSkipUnmatched=true
```

**在修好之前，要改行为只能改 `ImmerseConfig.kt` 里的默认值然后重新编译安装。**

---

## 调试

`ImmerseConfig.KEY_DEBUG_DUMP`（默认关）打开后会：

* 打印每个挂载且有尺寸的控件的 `id / 类名 / 坐标 / 尺寸 / alpha / visibility`；
* 在 `Activity#onResume` 后按 2.5s 间隔 dump 12 轮完整视图树（含文本、父类、LayoutParams）。

> ⚠️ dump 一轮就是 900 行，**会把 logcat 缓冲区冲爆导致丢日志**。
> 排查「hook 到底有没有跑」时务必先把它关掉，否则会得到完全错误的结论。
