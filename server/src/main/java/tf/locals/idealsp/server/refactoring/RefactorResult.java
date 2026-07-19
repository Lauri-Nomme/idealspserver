package tf.locals.idealsp.server.refactoring;

public class RefactorResult {
  private String operation;
  private boolean applied;
  private String failureReason;

  public RefactorResult() {}

  public RefactorResult(String operation, boolean applied, String failureReason) {
    this.operation = operation;
    this.applied = applied;
    this.failureReason = failureReason;
  }

  public String getOperation() { return operation; }
  public void setOperation(String operation) { this.operation = operation; }
  public boolean isApplied() { return applied; }
  public void setApplied(boolean applied) { this.applied = applied; }
  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
