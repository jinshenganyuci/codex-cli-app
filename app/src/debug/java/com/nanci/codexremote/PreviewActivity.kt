package com.nanci.codexremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nanci.codexremote.ui.content.*
import com.nanci.codexremote.ui.shell.*
import org.json.JSONObject

/** Debug-only deterministic fixtures; does not connect to a server or resolve real requests. */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val markdownErrors=markdownInlineSmokeChecks()+userMessageWrapperSmokeChecks()
        java.io.File(filesDir,"markdown-checks.txt").writeText(if(markdownErrors.isEmpty())"PASS: 6 inline Markdown and 4 async wrapper regressions" else markdownErrors.joinToString("\n"))
        enableEdgeToEdge()
        val scenario=intent.getStringExtra("scenario") ?: "tool_question"
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF0D0D0D),onPrimary=Color.White,
                secondary=Color(0xFF5D5D5D),background=Color(0xFFFAFAFA),surface=Color.White,onSurface=Color(0xFF0D0D0D))) {
                FixtureScreen(scenario,{finish()})
            }
        }
    }
}

@Composable
private fun FixtureScreen(scenario: String,onClose: () -> Unit) {
    var result by remember { mutableStateOf("") }
    var planOpen by remember { mutableStateOf(scenario=="plan_reader") }
    var dismissed by remember { mutableStateOf(false) }
    val record:(String)->Unit={result=it}
    val shell=ShellState(hostName="NancideMac-Mini.local",title="想我提问一加一等于几 …",subtitle="聊天",connected=true)
    val actions=ShellActions(onBack=onClose,onNewChat={record("新聊天")},onRename={record("重命名")},onArchive={record("归档")},onCopyId={record("复制 ID")})
    Box(Modifier.fillMaxSize().background(Color(0xFFFAFAFA)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        if(planOpen) PlanReader(FixturePlan,onClose={if(scenario=="plan_reader")onClose() else planOpen=false},onCopy={record("已复制计划")},onDownload={record("已请求下载计划")})
        else Column(Modifier.fillMaxSize()) {
            if(scenario in listOf("tool_question","approval","elicitation"))Spacer(Modifier.height(68.dp))
            Box(Modifier.weight(1f)) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(top=100.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
                    if(scenario=="async") {
                        MessageContent(JSONObject().put("type","agentMessage").put("text","一加一等于几？请在弹窗中回答。"),onCopy={record("已复制")})
                        MessageContent(JSONObject().put("type","userMessage").put("text","我还没回答重新弹窗"))
                        MessageContent(JSONObject().put("type","agentMessage").put("text","一加一等于几？"),onCopy={record("已复制")})
                    } else if(scenario=="plan_decision") {
                        PlanPreview(FixturePlan,onOpen={planOpen=true},onCopy={record("已复制计划")},onDownload={record("已请求下载计划")})
                    } else if(scenario=="approval") {
                        MessageContent(JSONObject().put("type","agentMessage").put("text","需要你批准后，我会继续执行。"),onCopy={record("已复制")})
                    }
                }
            }
            if(!dismissed) Column(Modifier.padding(horizontal=16.dp).padding(bottom=8.dp)) {
                when(scenario) {
                    "tool_question" -> ToolUserInputCard(JSONObject(FixtureQuestions),onSubmit={record(it.toString())})
                    "async" -> AsyncQuestionCard(JSONObject().put("question","一加一等于几？"),onClose={dismissed=true;record("关闭")},onSkip={dismissed=true;record("跳过")},onSubmit={record("发送：$it")})
                    "plan_decision" -> PlanDecisionCard(planId="preview-plan",onImplement={record("执行计划")},onRevise={record("修改：$it")},onSkip={dismissed=true;record("跳过")})
                    "approval" -> ApprovalCard("item/commandExecution/requestApproval",JSONObject(FixtureApproval),onReply={value,feedback ->record(value.toString()+(feedback?.let{" / $it"} ?: ""))})
                    "elicitation" -> ElicitationCard(JSONObject(FixtureForm),onReply={record(it.toString())},onOpenUrl={record("打开：$it")})
                }
            }
            if(scenario=="async" || dismissed) Composer(ComposerState(running=!dismissed,placeholder="在 NancideMac-Mini…"),ComposerActions(onStop={record("停止")},onTextChange={record(it)}))
        }
        if(!planOpen)ChatChrome(shell,actions,Modifier.align(Alignment.TopCenter))
        if(result.isNotEmpty()) Text(result,Modifier.align(Alignment.TopCenter).padding(horizontal=24.dp).padding(top=64.dp).background(Color.White),fontSize=10.sp,color=Color(0xFF5D5D5D),maxLines=2)
    }
}

private val FixtureQuestions="""
{"questions":[
 {"id":"purpose","header":"网页用途","question":"你希望做哪一类网页？","isOther":true,"isSecret":false,"options":[{"label":"实用工具（推荐）","description":"完成计算、查询或整理等具体任务。"},{"label":"展示页面","description":"用于介绍个人、作品或一个项目。"},{"label":"其他方向","description":"通过文字说明你的想法。"}]},
 {"id":"access","header":"访问范围","question":"这个网页准备给谁使用？","isOther":true,"isSecret":false,"options":[{"label":"自己或局域网（推荐）","description":"先在自己的设备或局域网内使用。"},{"label":"公开访问","description":"让其他人也能通过互联网访问。"}]},
 {"id":"starting","header":"起步方式","question":"你希望先做到什么程度？","isOther":true,"isSecret":false,"options":[{"label":"先做基础版本（推荐）","description":"先完成核心功能，再逐步完善。"},{"label":"完整设计","description":"提前规划更完整的页面与功能。"}]}
]}
""".trimIndent()

private val FixtureApproval="""
{"threadId":"preview-thread","turnId":"preview-turn","itemId":"preview-command","command":"npm run build","cwd":"/tmp/codex-mobile-preview","reason":"需要运行构建来验证代码。","availableDecisions":["accept","acceptForSession","decline","cancel"]}
""".trimIndent()

private val FixtureForm="""
{"mode":"form","serverName":"本地工具","message":"请选择本次工具任务的参数。","_meta":{"persist":["session","always"]},"requestedSchema":{"type":"object","required":["name","mode","count"],"properties":{
 "name":{"type":"string","title":"任务名称","minLength":2},
 "mode":{"type":"string","title":"运行方式","oneOf":[{"const":"preview","title":"先预览"},{"const":"run","title":"执行"}]},
 "count":{"type":"integer","title":"数量","minimum":1,"maximum":10,"default":1},
 "save":{"type":"boolean","title":"保存结果","default":true},
 "formats":{"type":"array","title":"输出格式","items":{"type":"string","enum":["JSON","CSV","Markdown"]},"maxItems":2}
}}}
""".trimIndent()

private val FixturePlan="""
# 局域网单位转换网页计划

## 目标
制作一个供自己和局域网设备使用的单位转换网页。第一版采用现代卡片风格，兼顾电脑和手机，功能做到日常使用够用的程度。

## 功能与实现
- 提供四类转换：
  - **长度**：毫米、厘米、米、千米、英寸、英尺。
  - **重量**：毫克、克、千克、吨、盎司、磅。
  - **面积**：平方厘米、平方米、平方千米、公顷、平方英尺、英亩。
  - **温度**：摄氏度、华氏度、开尔文。
- 选择类别和输入、输出单位后，输入数字即可实时显示结果；支持一键交换单位和清空输入。
- 对空输入和无效数字给出清晰提示，结果保留合理精度。
- 使用 HTML、CSS 和 JavaScript 实现；换算在浏览器内完成，第一版无需数据库或后端 API。

## 验证
- 检查常见单位的换算结果。
- 在手机和电脑浏览器中验证显示与交互。
""".trimIndent()
