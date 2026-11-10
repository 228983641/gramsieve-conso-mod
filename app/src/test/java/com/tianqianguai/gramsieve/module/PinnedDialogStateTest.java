package com.tianqianguai.gramsieve.module;

import org.junit.Test;
import static org.junit.Assert.*;

public class PinnedDialogStateTest {
    static class MethodCell {
        boolean pinned;
        boolean drawPin = true;
        public boolean getIsPinned() { return pinned; }
    }
    static class FieldCell { boolean drawPin = true; }
    static class Dialog { boolean pinned = true; }
    static class DialogCell { Dialog currentDialog = new Dialog(); }

    @Test public void authoritativeFalseDoesNotUseStaleField() {
        assertFalse(PinnedDialogState.isPinned(new MethodCell()));
    }
    @Test public void recycledCellReadsCurrentState() {
        MethodCell cell = new MethodCell();
        cell.pinned = true;
        assertTrue(PinnedDialogState.isPinned(cell));
        cell.pinned = false;
        assertFalse(PinnedDialogState.isPinned(cell));
    }
    @Test public void forksFallBackToFields() {
        assertTrue(PinnedDialogState.isPinned(new FieldCell()));
        assertTrue(PinnedDialogState.isPinned(new DialogCell()));
        assertFalse(PinnedDialogState.isPinned(new Object()));
        assertFalse(PinnedDialogState.isPinned(null));
    }
}
