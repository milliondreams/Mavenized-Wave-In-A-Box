public $if(m.inner)$static$endif$ final class $m.javaType$ {

  $m.nestedMessages: {nested|$class(m=nested)$}$

  private $m.javaType$() {
  }

  /**
   * Structurally compares one message to another.
   *
   * @return whether two messages are structurally equal
   */
  public static boolean isEqual($m.name$ m1, $m.name$ m2) {
    $m.fields: {f|$returnFalseIfNotEqual(f=f, target1="m1", target2="m2")$}$
    return true;
  }

  /**
   * Compares two lists of $m.name$s using isEqual.
   */
  public static boolean areAllEqual(List<? extends $m.name$> m1,
      List<? extends $m.name$> m2) {
    if (m1.size() != m2.size()) return false;
    Iterator<? extends $m.name$> i1 = m1.iterator();
    Iterator<? extends $m.name$> i2 = m2.iterator();
    while (i1.hasNext()) {
      if (!isEqual(i1.next(), i2.next())) return false;
    }
    return true;
  }

  /**
   * Structurally calculates the hash code of a message.
   *
   * @return the hash code of a message
   */
  public static int getHashCode($m.name$ message) {
    int result = 1;
    $m.fields: {f|$addHashCode(f=f, shift="31", result="result", target="message")$}$
    return result;
  }
}
