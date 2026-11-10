# 历史卡片角标设计

## 意图

在历史卡片封面右上角显示历史接口下发的角标文本。

## 范围与约束

- 历史数据目前把 `HistoryItem.badge` 映射到 `VideoCardData.badgeText`。
- `SmallVideoCard` 已在封面右上角渲染 `VideoCardData.coverBadges`。
- HTTP 历史模型包含 `badge`，但归一化后的 `biliapi.entity.user.HistoryItem` 当前把它丢弃了。要在该映射中把它传递下去（gRPC 来源默认为空），然后为 `VideoCardData` 增加专用的 `historyBadgeText` 字段，并把非空值加入 `coverBadges`。
- 不要用 `historyBusiness` 判断是否为历史卡片：未知历史项可能没有业务值。
- `badgeText` 保留给充电角标，其他卡片现有角标保持不变。
- 空白角标文本不应渲染。无需改动 HTTP API 或布局。

## 方案

- **选定：让 HTTP 角标穿过归一化历史模型，然后增加历史专用 UI 字段并扩展现有角标列表。** 复用现有渲染，兼容未知历史项，且不改变其他卡片充电角标的语义。
- 在所有卡片上显示 `badgeText`。否决原因：可能会在其他地方暴露历史专用文本，或重复显示充电角标。
- 单独实现一个历史专用渲染器。否决原因：现有封面角标 UI 已提供所需位置。

## 验收

每张历史卡片（包括未知历史项类型）都在封面右上角显示非空角标文本；空角标不显示任何内容；非历史卡片的角标保持不变。