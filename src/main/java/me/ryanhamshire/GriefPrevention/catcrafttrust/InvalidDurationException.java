package me.ryanhamshire.GriefPrevention.catcrafttrust;

public class InvalidDurationException extends Exception
{
    private final InvalidDurationReason reason;

    public InvalidDurationException(InvalidDurationReason reason)
    {
        super(reason.name());
        this.reason = reason;
    }

    public InvalidDurationException(InvalidDurationReason reason, Throwable cause)
    {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public InvalidDurationReason reason()
    {
        return reason;
    }
}
