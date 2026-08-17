# ADR-0001：选择 Kotlin 与 Compose Multiplatform

状态：Accepted  
日期：2026-08-17

## 背景

Worldloom 的目标平台是 Android、iOS 与 Desktop。核心体验由叙事时间线、卡牌、角色面板、世界工坊和轻量 2D 动效构成，不以实时物理或 3D 场景为主。

产品需要在卡牌表现、信息密度、跨端复用和工程复杂度之间取得平衡。

## 决策

采用 Kotlin Multiplatform 共享领域逻辑和基础设施接口，采用 Compose Multiplatform 共享主要 UI。

目标共享范围：

- 世界模型与规则引擎；
- Command、Event 与 Reducer；
- Agent Runtime 和 Context Projector；
- 世界包 Schema；
- 网络与序列化；
- 数据访问接口；
- Design Token、卡牌和主要游戏 UI。

平台代码保留：

- 凭据保险箱；
- 文件选择、导入和分享；
- 音频、触觉和通知；
- 窗口、生命周期和商店集成；
- 平台性能测量与打包。

## 理由

- 最大化现有 Kotlin 代码与经验的复用；
- 叙事、卡牌和编辑器是声明式 UI 的优势场景；
- Compose Canvas 足以完成目标中的卡牌、2.5D 视差和轻粒子；
- 共享规则和 Agent 边界可以减少各端行为差异；
- Desktop 能够作为内容制作与快速迭代平台。

## 代价

- Desktop 需要携带精简 JVM Runtime，启动和基础内存高于部分原生方案；
- iOS 构建、签名与真机调试仍然需要 macOS 和 Xcode；
- 跨端模糊、Shader 和复杂媒体能力需要额外验证；
- Compose 不是实时 3D 或物理游戏引擎；
- 必须主动管理重组、绘制缓存和每帧内存分配。

## 未选择的方案

### Godot

跨端和 2D 游戏能力优秀，但当前产品更偏信息、卡牌和编辑器。采用 Godot 会增加 Agent、存档和原生安全存储的集成成本。

### Unity

适合 3D、复杂特效和成熟商业美术管线，但对当前范围过重。

### Flutter

同样适合跨端 UI，但不能复用已有 Kotlin 代码和 Android 设计，且需要重新建立 Agent 与领域层技术栈。

## 重新评估触发条件

满足以下任一条件时重新评估 Godot：

- 核心玩法改为实时地图移动；
- 同屏需要大量动态单位；
- 骨骼动画成为主要角色表现方式；
- 实时光照、后处理或物理效果成为核心卖点；
- Compose 版本经过真机优化后仍无法达到既定帧时间目标。
