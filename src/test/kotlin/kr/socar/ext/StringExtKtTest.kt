package kr.socar.ext

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class StringExtKtTest {

    @Test
    fun snakeToLowerCamelCase() {
        Assertions.assertEquals(
            "code01WrittenBelow",
            "code_01_written_below".snakeToLowerCamelCase()
        )
    }
}
