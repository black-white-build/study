package com.heartpilot.module.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.heartpilot.module.knowledge.entity.enums.KnowledgeEvidenceLevel;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 知识库 Markdown 解析器：校验从内置文档中提取的元数据与内容哈希。 */
class KnowledgeMarkdownParserTest {
    /** 解析 relationship-communication.md，断言文档ID、版本、证据等级、正文与 64 位哈希均符合约定。 */
    @Test
    void repositoryKnowledgeHasRequiredMetadata() throws Exception {
        KnowledgeMarkdownParser.ParsedDocument document =
                new KnowledgeMarkdownParser()
                        .parse(Path.of("../knowledge/relationship-communication.md"));
        assertEquals("relationship-communication", document.documentId());
        assertEquals("1.0.0", document.metadata().contentVersion());
        assertEquals(KnowledgeEvidenceLevel.MEDIUM, document.metadata().evidenceLevel());
        assertFalse(document.body().isBlank());
        assertEquals(64, document.contentHash().length());
    }
}
