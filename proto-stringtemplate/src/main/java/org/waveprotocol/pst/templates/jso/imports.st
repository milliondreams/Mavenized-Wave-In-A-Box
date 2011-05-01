
$importUtil(m=m,p=m.packageBase)$

// Import interface and impl of referenced messages.
$m.referencedMessages: {t|
  import $t.fullName$;
  import $t.fullJavaType$;
}$

$m.referencedEnums: {t|
  import $t.fullName$;
}$
