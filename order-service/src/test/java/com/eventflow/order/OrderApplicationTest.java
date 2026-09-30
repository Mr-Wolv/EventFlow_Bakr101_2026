package com.eventflow.order;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;

/**
 * The bootstrap main() exists so `java -jar` works; unit-testing it just verifies the
 * Spring context wires up outside Docker. Stderr is suppressed so expected startup
 * logs don't pollute the test output.
 */
class OrderApplicationTest {

    @Test
    void applicationContextBootsViaMain() {
        PrintStream originalErr = System.err;
        System.setErr(new PrintStream(java.io.OutputStream.nullOutputStream()));
        try {
            OrderApplication.main(new String[0]);
        } finally {
            System.setErr(originalErr);
        }
    }
}
