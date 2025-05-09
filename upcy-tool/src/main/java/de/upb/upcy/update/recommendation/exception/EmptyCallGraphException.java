package de.upb.upcy.update.recommendation.exception;

/**
 * Exception indicating that no call graph could be computed or the call graph was empty
 *
 * @author adann
 */
public class EmptyCallGraphException extends Exception {
  public EmptyCallGraphException(String s) {
    super(s);
  }
}
