/*
 * Copyright (c) 2021 2bllw8
 * SPDX-License-Identifier: GPL-3.0-only
 */
package eu.bbllw8.anemo.editor.commands;

import androidx.annotation.NonNull;

public class EditorCommand {

    private EditorCommand() {
    }

    public static class Delete extends EditorCommand {
        @NonNull
        private final String toDelete;

        public Delete(@NonNull String toDelete) {
            this.toDelete = toDelete;
        }

        @NonNull
        public String getToDelete() {
            return toDelete;
        }
    }

    public static class Find extends EditorCommand {
        @NonNull
        private final String toFind;

        public Find(@NonNull String toFind) {
            this.toFind = toFind;
        }

        @NonNull
        public String getToFind() {
            return toFind;
        }
    }

    public static class Substitute extends EditorCommand {
        @NonNull
        private final String toFind;
        @NonNull
        private final String replaceWith;

        public Substitute(@NonNull String toFind,
                          @NonNull String replaceWith) {
            this.toFind = toFind;
            this.replaceWith = replaceWith;
        }

        @NonNull
        public String getToFind() {
            return toFind;
        }

        @NonNull
        public String getReplaceWith() {
            return replaceWith;
        }
    }
}
