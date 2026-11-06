package me.sevj6.funCore.economy;

/** The outcome of an {@link Economy} operation. Anything other than {@link #SUCCESS} means nothing changed. */
public enum TransactionResult {
    SUCCESS,
    /** The amount was zero, negative, or over {@link Cash#MAX}. */
    INVALID_AMOUNT,
    /** A player involved has never joined the server, so they have no account. */
    NO_ACCOUNT,
    /** The payer doesn't have enough cash. */
    INSUFFICIENT_FUNDS,
    /** The receiver's balance would go over {@link Cash#MAX}. */
    BALANCE_LIMIT,
    /** A player tried to pay themselves. */
    SAME_ACCOUNT;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
