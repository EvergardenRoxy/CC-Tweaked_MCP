// SPDX-License-Identifier: MPL-2.0
package cctrun.cli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LauncherTest {
    @TempDir Path dir;
    @Test void java8LauncherSwitchesAndPreservesExitCodeOrExplainsMissingJava() throws Exception {
        var configured=System.getenv("CCT_SMOKE_JAVA8_ROOT");
        assumeTrue(configured!=null,"set CCT_SMOKE_JAVA8_ROOT for the real Java 8 launcher check");
        String exe=System.getProperty("os.name").toLowerCase().startsWith("windows")?"java.exe":"java";
        var java8=Path.of(configured,"bin",exe);
        assumeTrue(Files.isExecutable(java8));
        for(boolean unavailable:List.of(false,true)) {
            var command=List.of(java8.toString(),"-Dcctrun.java.searchOnly="+(unavailable?dir:System.getProperty("java.home")),"-cp",System.getProperty("java.class.path"),Launcher.class.getName(),"version");
            var output=dir.resolve("result-"+unavailable);
            var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().remove("CCT_RUN_REEXEC");
            var process=builder.start();
            if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();fail("launcher did not exit");}
            String text=Files.readString(output);
            assertEquals(unavailable?69:0,process.exitValue(),text);
            assertTrue(text.contains(unavailable?"needs Java 17":"is too old; using"),text);
        }
    }
}
