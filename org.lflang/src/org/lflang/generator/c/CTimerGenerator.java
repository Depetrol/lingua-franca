package org.lflang.generator.c;

import java.util.List;
import org.lflang.generator.GeneratorBase;
import org.lflang.generator.TimerInstance;

/**
 * Generates C code to declare and initialize timers.
 * 
 * @author {Edward A. Lee <eal@berkeley.edu>}
 * @author {Soroush Bateni <soroush@utdallas.edu>
 */

public class CTimerGenerator {
    public static String generateInitializer(TimerInstance timer) {
        var triggerStructName = CUtil.reactorRef(timer.getParent()) + "->_lf__"  + timer.getName();
        var offset = GeneratorBase.timeInTargetLanguage(timer.getOffset());
        var period = GeneratorBase.timeInTargetLanguage(timer.getPeriod());
        var mode = timer.getMode(false);
        var modeRef = mode != null ? 
            "&"+CUtil.reactorRef(mode.getParent())+"->_lf__modes["+mode.getParent().modes.indexOf(mode)+"];" :
            "NULL";
        
        return String.join("\n", List.of(
            "// Initializing timer "+timer.getFullName()+".",
            triggerStructName+".offset = "+offset+";",
            triggerStructName+".period = "+period+";",
            "_lf_timer_triggers[_lf_timer_triggers_count++] = &"+triggerStructName+";",
            triggerStructName+".mode = "+modeRef+";"
        ));
    }

    public static String generateDeclarations(int timerCount) {
        return String.join("\n", List.of(
                    "// Array of pointers to timer triggers to be scheduled in _lf_initialize_timers().",
                    "trigger_t** _lf_timer_triggers" + (timerCount > 0 ? "["+timerCount+"]" : " = NULL") + ";",
                    "int _lf_timer_triggers_size = "+timerCount+";"
                )); 
    }

    public static String generateLfInitializeTimer(int timerCount) {
        return String.join("\n", 
            "void _lf_initialize_timers() {",
            (timerCount > 0 ?
            String.join("\n",
            "    for (int i = 0; i < _lf_timer_triggers_size; i++) {",
            "        if (_lf_timer_triggers[i] != NULL) {",
            "            _lf_initialize_timer(_lf_timer_triggers[i]);",
            "        }",
            "    }"
            ) :
            ""),
            "}"
        );
    }
}
