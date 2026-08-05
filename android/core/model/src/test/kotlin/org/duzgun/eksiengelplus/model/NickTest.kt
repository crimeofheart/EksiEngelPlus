package org.duzgun.eksiengelplus.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NickTest {

    @Test
    fun `single word nick is unchanged`() {
        assertThat("ssg".toEksiSlug()).isEqualTo("ssg")
    }

    @Test
    fun `spaces become hyphens`() {
        assertThat("bir iki uc".toEksiSlug()).isEqualTo("bir-iki-uc")
    }

    @Test
    fun `nicks observed live during the spike`() {
        assertThat("0 derece".toEksiSlug()).isEqualTo("0-derece")
        assertThat("ben ne diyorum sen ne diyorsun".toEksiSlug())
            .isEqualTo("ben-ne-diyorum-sen-ne-diyorsun")
    }

    @Test
    fun `surrounding whitespace is trimmed before substitution`() {
        assertThat("  goker  ".toEksiSlug()).isEqualTo("goker")
        assertThat(" iki kelime ".toEksiSlug()).isEqualTo("iki-kelime")
    }
}
