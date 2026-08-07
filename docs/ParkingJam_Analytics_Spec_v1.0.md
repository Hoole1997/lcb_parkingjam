# 《停车大挪移》核心事件埋点规范

> 项目代号：LCB_ParkingJam  
> 文档版本：v1.0 Draft  
> 更新日期：2026-08-05  
> 适用范围：首页、关卡选择页、游戏局内页、关卡结果弹窗  
> 文档状态：已实现  
> 上报接口：`ReportDataManager.reportData(eventName, data)`

---

## 1. 文档目标

本文定义首页、关卡选择页和游戏局内页的核心行为事件，作为产品、Android、Web 游戏层、数据和测试共同使用的实现与验收基线。

本期共定义 10 个事件：

| 事件名 | 事件显示名 | 模块 |
|---|---|---|
| `page_show` | 页面曝光 | 公共 |
| `page_leave` | 页面离开 | 公共 |
| `home_primary_click` | 首页主按钮点击 | 首页 |
| `home_level_select_click` | 首页选关入口点击 | 首页 |
| `level_select_click` | 关卡节点点击 | 关卡选择 |
| `level_continue_click` | 选关页继续点击 | 关卡选择 |
| `level_start` | 关卡开始 | 游戏局内 |
| `game_action_click` | 局内操作点击 | 游戏局内 |
| `level_result` | 关卡结果 | 关卡结果 |
| `result_action_click` | 结果弹窗操作 | 关卡结果 |

## 2. 通用口径

### 2.1 命名与数据类型

- 事件名、属性名和字符串枚举值统一使用小写 `snake_case`，实现时不得自行改名。
- 整数属性按整数上报，不得转换为带单位的字符串。
- `level_number` 的有效范围为 `1–30`。
- `duration_ms` 单位为毫秒，取非负整数；客户端计算时应使用单调时钟，避免系统时间被修改后产生负数。
- 无属性事件使用空属性集合，不传无意义的空字符串或占位值。

### 2.2 页面展示周期

一次页面展示周期从该页面成功上报 `page_show` 开始，到对应的 `page_leave` 结束。

- `page_show` 必须在业务页面完成首帧展示后上报，同一次页面展示只允许上报一次。
- `page_leave` 在页面离开前台或关闭时上报，一次页面展示最多对应一次离开事件。
- 页面进入后台后再次回到前台，视为一次新的页面展示周期，应重新产生一组 `page_show` 和 `page_leave`。
- 页面未完成首帧、没有产生 `page_show` 时，不产生与其配对的 `page_leave`。
- `page_leave.duration_ms` 从本次 `page_show` 的成功上报时刻开始计算，到页面离开前台或关闭时结束。

### 2.3 关卡运行周期

一次关卡运行周期从 `level_start` 开始，到 `level_result` 结束，或因返回首页、重新开始、刷新等操作中断。

- 关卡完成数据初始化并进入可操作状态时上报 `level_start`。
- 同一关重新初始化时必须产生新的 `level_start`，包括重试、重开和刷新。
- 每次 `level_start` 最多对应一次 `level_result`。
- `level_result.duration_ms` 从对应 `level_start` 的成功上报时刻开始计算。
- 胜利或失败弹窗不是独立业务页面，不单独上报 `page_show` 或 `page_leave`。

## 3. 页面与首页事件

### 3.1 `page_show` — 页面曝光

首页、关卡选择页或游戏局内页完成首帧展示时上报。同一次页面展示只上报一次。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `page_name` | 页面名称 | 字符串 | 是 | 发生曝光的业务页面 | `game_home` | 游戏首页 |
|  |  |  |  |  | `level_select` | 关卡选择页 |
|  |  |  |  |  | `gameplay` | 游戏局内页 |

### 3.2 `page_leave` — 页面离开

首页、关卡选择页或游戏局内页离开前台或被关闭时上报；一次页面展示最多对应一次离开。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `page_name` | 页面名称 | 字符串 | 是 | 离开的业务页面 | `game_home` | 游戏首页 |
|  |  |  |  |  | `level_select` | 关卡选择页 |
|  |  |  |  |  | `gameplay` | 游戏局内页 |
| `duration_ms` | 停留时长 | 整数 | 是 | 从本次页面曝光到离开的毫秒数，取非负整数 | — | — |

### 3.3 `home_primary_click` — 首页主按钮点击

用户点击首页“继续游戏”主按钮，且系统准备打开当前进度关卡时上报。全部关卡完成、按钮无后续动作时不上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 |
|---|---|---|---|---|---|
| `target_level` | 目标关卡 | 整数 | 是 | 本次准备进入的关卡编号，范围 `1–30` | — |

### 3.4 `home_level_select_click` — 首页选关入口点击

用户点击首页关卡选择入口，且系统准备打开关卡选择页时上报。

该事件无业务属性。

## 4. 关卡选择事件

### 4.1 `level_select_click` — 关卡节点点击

用户点击已完成、当前或已解锁的关卡节点，且系统准备进入该关卡时上报。锁定节点被忽略时不上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 被点击的关卡编号，范围 `1–30` | — | — |
| `level_status` | 关卡状态 | 字符串 | 是 | 点击动作发生前的关卡状态 | `completed` | 已完成 |
|  |  |  |  |  | `current` | 当前进度关 |
|  |  |  |  |  | `available` | 已解锁可进入 |

### 4.2 `level_continue_click` — 选关页继续点击

用户点击关卡选择页底部继续按钮，且系统准备进入当前进度关卡时上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 |
|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 当前进度关卡编号，范围 `1–30` | — |

## 5. 游戏局内事件

### 5.1 `level_start` — 关卡开始

关卡数据完成初始化并进入可操作状态时上报。重新开始同一关也需要重新上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 当前关卡编号，范围 `1–30` | — | — |
| `entry` | 进入来源 | 字符串 | 是 | 触发本次关卡初始化的入口 | `home` | 首页继续游戏 |
|  |  |  |  |  | `level_select` | 选关节点或选关页继续按钮 |
|  |  |  |  |  | `next_level` | 胜利后进入下一关 |
|  |  |  |  |  | `retry` | 失败后重试 |
|  |  |  |  |  | `restart` | 局内重新开始本关 |
|  |  |  |  |  | `refresh` | 激励广告奖励成功后刷新本关 |

### 5.2 `game_action_click` — 局内操作点击

用户点击局内功能按钮且操作被接受时上报。无效点击、重复点击被拦截或操作未执行时不上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 当前关卡编号，范围 `1–30` | — | — |
| `action` | 操作类型 | 字符串 | 是 | 被接受的局内功能操作 | `back` | 返回游戏首页 |
|  |  |  |  |  | `restart` | 免费重新开始本关 |
|  |  |  |  |  | `refresh` | 激励广告奖励成功且刷新真正执行 |
|  |  |  |  |  | `sound_on` | 开启声音 |
|  |  |  |  |  | `sound_off` | 关闭声音 |

## 6. 关卡结果事件

### 6.1 `level_result` — 关卡结果

胜利条件成立并展示胜利弹窗，或候车位已满且无法继续并展示失败弹窗时上报。每次关卡开始最多上报一次。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 当前关卡编号，范围 `1–30` | — | — |
| `result` | 关卡结果 | 字符串 | 是 | 本次关卡运行的最终结果 | `win` | 胜利 |
|  |  |  |  |  | `fail` | 失败 |
| `duration_ms` | 关卡耗时 | 整数 | 是 | 从对应 `level_start` 到结果产生的毫秒数，取非负整数 | — | — |

### 6.2 `result_action_click` — 结果弹窗操作

用户点击胜利或失败弹窗内可执行的按钮时上报。

| 属性名 | 属性显示名 | 类型 | 必填 | 属性说明 | 枚举值 | 枚举值说明 |
|---|---|---|---|---|---|---|
| `level_number` | 关卡编号 | 整数 | 是 | 当前关卡编号，范围 `1–30` | — | — |
| `result` | 结果弹窗 | 字符串 | 是 | 按钮所在的结果弹窗 | `win` | 胜利弹窗 |
|  |  |  |  |  | `fail` | 失败弹窗 |
| `action` | 操作类型 | 字符串 | 是 | 结果弹窗内被点击的按钮 | `next_level` | 胜利后进入下一关 |
|  |  |  |  |  | `retry` | 失败后免费重试 |
|  |  |  |  |  | `home` | 返回游戏首页 |

## 7. 推荐事件顺序

### 7.1 首页继续进入游戏

```text
page_show(page_name=game_home)
home_primary_click(target_level=N)
page_leave(page_name=game_home, duration_ms=...)
page_show(page_name=gameplay)
level_start(level_number=N, entry=home)
```

### 7.2 首页进入选关并点击节点

```text
page_show(page_name=game_home)
home_level_select_click
page_leave(page_name=game_home, duration_ms=...)
page_show(page_name=level_select)
level_select_click(level_number=N, level_status=...)
page_leave(page_name=level_select, duration_ms=...)
page_show(page_name=gameplay)
level_start(level_number=N, entry=level_select)
```

### 7.3 胜利后进入下一关

```text
level_result(level_number=N, result=win, duration_ms=...)
result_action_click(level_number=N, result=win, action=next_level)
level_start(level_number=N+1, entry=next_level)
```

### 7.4 失败后重试

```text
level_result(level_number=N, result=fail, duration_ms=...)
result_action_click(level_number=N, result=fail, action=retry)
level_start(level_number=N, entry=retry)
```

## 8. 开发与验收检查表

- [ ] 所有事件名、属性名和枚举值与本文完全一致。
- [ ] 首页、选关页和游戏页均在首帧完成后上报 `page_show`。
- [ ] 每个页面展示周期最多上报一次 `page_show` 和一次 `page_leave`。
- [ ] 页面切后台、返回前台和 Activity 关闭场景的事件不重复、不遗漏。
- [ ] 锁定关卡、无动作按钮和未被接受的操作不产生点击事件。
- [ ] 每次关卡重新初始化均产生新的 `level_start`，并携带正确 `entry`。
- [ ] 每次 `level_start` 最多产生一次 `level_result`。
- [ ] 页面停留时长与关卡耗时均使用单调时钟计算，且不小于 0。
- [ ] 关卡编号始终处于 `1–30` 范围内。
- [ ] 胜利、失败、下一关、重试、返回首页的事件顺序符合本文示例。

## 9. 已锁定的实现口径

1. 候车位满且无法继续、失败弹窗出现时，上报 `level_result(result=fail)`。
2. 刷新是激励广告操作。仅在奖励成功且刷新真正执行后，上报 `game_action_click(action=refresh)`，随后上报新的 `level_start(entry=refresh)`；广告取消、失败或重复点击不上报刷新成功事件。
3. 失败后通过激励广告解锁车位并继续时，失败事件已经结束上一关卡统计周期；解锁成功后使用 `level_start(entry=retry)` 开启新的统计周期，确保每次 `level_start` 最多对应一个 `level_result`。
4. 消除、排序、普通车位解锁和失败后的广告救援暂不扩展本规范的事件枚举；不得使用未登记的 `action` 值污染现有事件。
