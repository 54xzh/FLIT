package me.rerere.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDecisionTypeTest {
    @Test
    fun jevModelIdIsDetectedAsDecisionModel() {
        val model = Model(modelId = "typesafe-ai/JEV-1.13", type = ModelType.CHAT)

        assertEquals(ModelType.DECISION, model.withDetectedDecisionType().type)
    }

    @Test
    fun unrelatedModelKeepsItsExistingType() {
        val model = Model(modelId = "text-embedding-3-small", type = ModelType.EMBEDDING)

        assertEquals(ModelType.EMBEDDING, model.withDetectedDecisionType().type)
    }
}
