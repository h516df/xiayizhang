# 0.3 首发交接

包名 cn.privatereader，versionCode 3；更新源 h516df/xiayizhang（固定 ID 1426460841），资产前缀 xiayizhang。

更新组件复用 android-update-kit 0f55b6f14a99a792b1abe3a28d538ce15a2fae1f 的源码快照；公开 client id 不是密钥。只在用户点击后联网。APK 不含服务密钥、GitHub token 或签名私钥。正式包通过外部签名流程签名，Gradle 不读取或存放签名密码。

签名证书公开 SHA256：ec66c5260a5bb698fa0faa5414e20424fba96434f5849a254c12f3bf20a9244e。以后更新保持同一证书。

本版仅基础文本 JSON 导入；不支持 PNG/CHARX 或完整酒馆运行语义。真实 AI、真机选区/IME/选择器、私有源设备授权和系统覆盖安装未验，不用电脑下载校验替代。

本项目对该快照做了三项首发修复：每次更新操作独立取消与回调世代，取消后可重试且旧响应不能保存授权；只接受已发布的正式 Release；检查和下载前均验证仓库固定 ID 与名称。测试可选使用经校验的本地 Robolectric 公共运行时缓存，默认测试配置不变。

## 本轮增量验证（候选初验记录）

- assembleRelease 与 lintRelease 通过：0 error、14 warning（依赖更新、KTX和弃用建议等）。正式构建显式非 debuggable，包名/0.3/code3/min26/target36及四种ABI已从APK读回。
- 2项已发布版本过滤回归、7项取消/旧响应隔离/身份校验回归通过，均用合成响应，不访问真实网络或凭据。取消回归作为纯JVM状态机测试，不替代Android Keystore或设备验收。
- 新增更新页1项Compose入口检查尚未通过：Robolectric公开API35运行时下载/校验受阻。基础76项及清理22+5项沿用既有云端结果，没有因合并/签名重跑。无远端CI结论。
- 提交源码快照的常见凭据模式与禁入签名文件检查零命中；Gitleaks WSL包装器未得到成功回执，不声称Gitleaks通过。
- 尚未签名发布或执行实际下载回验；签名材料已专用创建并在本机受保护保存，未进入Git。

## 云端复核补记

在准确候选 head 44ae18674dc36be73fa670ed0ee43d056402edfa / tree 356e5141e908ec58191c0865ff9b7d453dd4c6d3 上，UpdateEntryUiTest.localReleaseNotesOpenWithoutChangingBooksOrStartingNetwork 已通过：1 test，0 failures/errors/skips。父侧现成云端离线缓存完成，构建47秒、用例6.762秒；原生截图核对0.3说明和可用检查按钮，返回书架后合成原文和历史未变。源码未改，本机不再重复该项；以上依赖下载阻断已由该复核解除。此补记只更新验收文档，不改变APK运行输入或版本说明。实际发布及下载复验结果以GitHub Release附件读回为准。
