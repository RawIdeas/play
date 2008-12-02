package play.exceptions;

/**
 * JPA exception
 */
public class JPAException extends PlayException {

    public JPAException(String message) {
        super(message, null);
    }

    public JPAException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getErrorTitle() {
        return "JPA error";
    }

    @Override
    public String getErrorDescription() {
        return String.format("A JPA error occured (%s): <strong>%s</strong>", getMessage(), getCause() == null ? "" : getCause().getMessage());
    }
}
