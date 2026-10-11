package dev.quickdrop.core.web;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super("No %s with id %s".formatted(what, id));
    }
}
