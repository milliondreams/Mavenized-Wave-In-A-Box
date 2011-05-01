$if (f.optional)$

  if (has$f.capName$()) {
    $result$ = ($shift$ * $result$) + $f.name$.hashCode();
  }

$else$

  $result$ = ($shift$ * $result$) + $f.name$.hashCode();

$endif$
