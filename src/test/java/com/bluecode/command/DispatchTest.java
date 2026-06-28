package com.bluecode.command;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DispatchTest {
    @ParameterizedTest
    @CsvSource(value = {
            "''|false|''|''",
            "'   '|false|''|''",
            "'hello'|false|''|''",
            "'/'|true|''|''",
            "'/help'|true|help|''",
            "'  /HELP  '|true|help|''",
            "'/help xx'|true|help|xx",
            "'/help  '|true|help|''",
            "'//double'|true|/double|''",
            "'/ /help'|true|''|/help"
    }, delimiter = '|')
    void parsesSlashForms(String input, boolean expectedSlash, String expectedName, String expectedArguments) {
        Dispatch.Parsed parsed = Dispatch.parse(input);

        assertEquals(expectedSlash, parsed.isSlash());
        assertEquals(expectedName, parsed.name());
        assertEquals(expectedArguments, parsed.arguments());
    }
}
