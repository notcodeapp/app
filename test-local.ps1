$ErrorActionPreference = 'Stop'
$root = (Resolve-Path "$PSScriptRoot/..").Path
$file = "$PSScriptRoot/app/build/local-test-classpath.txt"
if (!(Test-Path $file)) { throw 'Run build-local.cmd first.' }
$classpath = [IO.File]::ReadAllText($file, [Text.Encoding]::UTF8)
& "$root/.tools/jdk-17.0.21.0.2+1/bin/java.exe" -cp $classpath org.junit.runner.JUnitCore com.notcode.mobile.filesystem.WorkspacePathTest com.notcode.mobile.agent.AgentSafetyTest com.notcode.mobile.ProjectApprovalPolicyTest com.notcode.mobile.agent.AnalysisAgentTest com.notcode.mobile.ChatFileLinksTest com.notcode.mobile.ChatTimelineTest com.notcode.mobile.runtime.SafeTarExtractorTest com.notcode.mobile.browser.PreviewSecurityTest com.notcode.mobile.browser.BrowserDownloadTest com.notcode.mobile.data.ChatHistoryStoreTest com.notcode.mobile.network.AgentWebToolsTest com.notcode.mobile.git.UbuntuGitToolsTest com.notcode.mobile.agent.AgentRunnerRetryTest com.notcode.mobile.update.GithubReleaseTest com.notcode.mobile.chat.AttachmentClassifierTest com.notcode.mobile.llm.ImageGenerationProtocolTest com.notcode.mobile.agent.ImageGenerationToolsTest
exit $LASTEXITCODE
