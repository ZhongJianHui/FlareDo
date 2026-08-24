## Summary / 概要

<!-- Explain the problem and the smallest behavior change. / 说明问题及最小行为改动。 -->

## Platforms and risk / 平台与风险

<!-- List affected platforms and security, privacy, migration, or compatibility implications. / 列出受影响平台及安全、隐私、迁移或兼容性影响。 -->

## Verification / 验证

<!-- List exact commands and manual checks. Automated tests must not write to production Linux.do. / 列出准确命令和人工检查；自动化测试不得写入生产 Linux.do。 -->

- [ ] Relevant focused tests pass. / 相关聚焦测试已通过。
- [ ] `./gradlew ktlintCheck` and `git diff --check` pass, or the exception is explained. / 检查已通过，或已说明例外。
- [ ] Screenshot goldens changed only for intentional, reviewed visual changes. / 仅为有意且已评审的视觉改动更新截图 golden。

## Contributor checklist / 贡献者确认

- [ ] The change is focused and contains no credentials, cookies, tokens, private data, signing material, production response dumps, or unredacted logs. / 改动聚焦，且不含凭据、Cookie、令牌、隐私数据、签名材料、生产响应转储或未脱敏日志。
- [ ] Test fixtures are synthetic, minimized, and redacted; tests make no production Linux.do writes. / fixture 为合成、最小化、脱敏数据，测试不会写入生产 Linux.do。
- [ ] Comments and KDoc are in English and explain non-obvious invariants. / 注释与 KDoc 使用英文并解释不直观的不变量。
- [ ] User-facing UI and project documentation are updated in English and Simplified Chinese. / 面向用户的 UI 与项目文档已同步更新英文和简体中文。
- [ ] I have the right to license this contribution under AGPL-3.0 and have documented any third-party provenance. / 我有权以 AGPL-3.0 授权本贡献，并已说明第三方来源。
- [ ] I did not copy fluxdo Dart code, fixtures, text, renderers, or assets; any fluxdo comparison is limited to public API behavior. / 我未复制 fluxdo 的 Dart 代码、fixture、文案、渲染器或资产；相关核对仅限公开 API 行为。
- [ ] Published `main` commits will not be amended, squashed, rebased, force-pushed, or otherwise rewritten; corrections use follow-up commits. / 已发布到 `main` 的提交不会被改写，修复使用追加提交。

## Related issue / 相关 Issue

<!-- Closes #... -->
