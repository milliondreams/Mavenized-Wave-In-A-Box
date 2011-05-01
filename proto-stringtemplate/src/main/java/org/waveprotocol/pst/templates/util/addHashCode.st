$if (f.optional)$
  if ($target$.has$f.capName$()) {
$endif$

  $if (f.primitiveAndNotRepeated)$
    $result$ = ($shift$ * $result$) + (new $f.boxedJavaType$($target$.$f.getter$())).hashCode();
  $else$
    $result$ = ($shift$ * $result$) + $target$.$f.getter$().hashCode();
  $endif$

$if (f.optional)$
  }
$endif$
