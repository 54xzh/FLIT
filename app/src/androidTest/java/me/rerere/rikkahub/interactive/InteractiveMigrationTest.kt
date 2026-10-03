package me.rerere.rikkahub.interactive

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InteractiveMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrationRetainsMessagesAndComponentRowsCascadeWhenConversationIsDeleted() {
        helper.createDatabase("interactive-migration", 55).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','History','[]',1,1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("interactive-migration", 56, true, AppDatabase.MIGRATION_55_56)
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("INSERT INTO interactive_component_state VALUES('chat','message',0,0,'hash','card','{}',NULL,0)")
        db.query("SELECT title FROM ConversationEntity WHERE id='chat'").use {
            it.moveToFirst(); assertEquals("History", it.getString(0))
        }
        db.execSQL("DELETE FROM ConversationEntity WHERE id='chat'")
        db.query("SELECT COUNT(*) FROM interactive_component_state").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        db.close()
    }

    @Test fun submissionSnapshotsAreBackfilledWithoutChangingDrafts() {
        helper.createDatabase("interactive-snapshot-migration", 56).apply {
            execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) VALUES('chat','History','[]',1,1)")
            execSQL("INSERT INTO interactive_component_state VALUES('chat','submitted',0,0,'hash','card','{\"value\":2}','one',1)")
            execSQL("INSERT INTO interactive_component_state VALUES('chat','draft',0,0,'hash','card','{\"value\":3}',NULL,0)")
            close()
        }
        val db = helper.runMigrationsAndValidate("interactive-snapshot-migration", 57, true, AppDatabase.MIGRATION_56_57)
        db.query("SELECT dataModel, submittedDataModel FROM interactive_component_state WHERE messageId='submitted'").use {
            it.moveToFirst()
            assertEquals("{\"value\":2}", it.getString(0))
            assertEquals(it.getString(0), it.getString(1))
        }
        db.query("SELECT submittedDataModel FROM interactive_component_state WHERE messageId='draft'").use {
            it.moveToFirst()
            org.junit.Assert.assertTrue(it.isNull(0))
        }
        db.close()
    }
}
