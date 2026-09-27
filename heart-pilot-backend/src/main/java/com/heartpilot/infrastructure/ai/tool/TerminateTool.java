package com.heartpilot.infrastructure.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 终止工具（作用是让自主规划智能体能够合理地中断） */
@Component
public class TerminateTool {

    /**
     * 供 Agent 自主调用的终止动作。
     * 任务已完成或无法继续推进时由模型主动调用，标志本次工作结束。
     *
     * @return 固定返回"任务结束"，通知 Agent 停止后续工具调用
     */
    @Tool(
            description =
                    """
            Terminate the interaction when the request is met OR if the assistant cannot proceed further with the task.
            "When you have finished all the tasks, call this tool to end the work.
            """)
    public String doTerminate() {
        return "任务结束";
    }
}
