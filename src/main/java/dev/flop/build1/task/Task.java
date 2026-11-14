package dev.flop.build1.task;

import java.util.List;

public interface Task {
    String name();
    /** Called every client tick while running and not paused. */
    void tick();
    boolean finished();
    void stop();
    List<String> hud();
}
