package you.v50to.eatwhat.exception;

import you.v50to.eatwhat.data.enums.BizCode;

/** Throw through the transaction boundary so files, tags and catalog facts roll back together. */
public final class DuplicateFoodException extends BizException {
    private final long foodId;
    public DuplicateFoodException(long foodId) { super(BizCode.FOOD_NAME_EXISTS); this.foodId = foodId; }
    public long foodId() { return foodId; }
}
