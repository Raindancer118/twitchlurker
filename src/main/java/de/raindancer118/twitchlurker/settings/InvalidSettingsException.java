package de.raindancer118.twitchlurker.settings;

import java.util.List;

public class InvalidSettingsException extends RuntimeException {

    private final List<String> errors;

    public InvalidSettingsException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
