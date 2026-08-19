package org.suvia.orchestration;

import org.springframework.stereotype.Component;
import org.suvia.agent.intent.Capability;
import org.suvia.agent.intent.TaskSpec;

/**
 * Deterministic clarification wording. The policy decision to clarify comes
 * from intent classification; this component only turns it into a safe prompt
 * for the user and therefore does not add another model call.
 */
@Component
public class TaskClarificationService {

    public String question(TaskSpec taskSpec) {
        if (taskSpec.capabilities().contains(Capability.FILE_READ)
                || taskSpec.capabilities().contains(Capability.PDF_READ)) {
            return "请说明需要读取或分析的文件，并补充你希望得到的结果。";
        }
        if (taskSpec.capabilities().contains(Capability.FILE_WRITE)
                || taskSpec.capabilities().contains(Capability.PDF_WRITE)) {
            return "请说明要生成或修改的内容、文件格式和目标文件名。";
        }
        if (taskSpec.capabilities().contains(Capability.KNOWLEDGE_RETRIEVAL)) {
            return "请补充要查询的研究主题、论文或具体问题。";
        }
        if (taskSpec.capabilities().contains(Capability.WEB_SEARCH)
                || taskSpec.capabilities().contains(Capability.WEB_FETCH)) {
            return "请补充要检索的主题、时间范围或目标来源。";
        }
        if (taskSpec.capabilities().contains(Capability.TERMINAL_EXECUTION)) {
            return "请明确要执行的程序、参数和预期结果；系统只允许显式授权的命令。";
        }
        return "请说明你希望我执行哪类任务：检索论文、总结文献、分析文件，还是生成报告？";
    }
}
