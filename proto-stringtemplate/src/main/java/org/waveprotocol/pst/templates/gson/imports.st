
$importUtil(m=m,utilScope=m.packageBase,pojoScope=m.packageBase+".impl")$

// Import interface, impl and pojo impl of referenced messages.
$m.referencedMessages: {t|
  import $t.fullName$;
  import $t.fullJavaType$;
}$

$m.referencedEnums: {t|
  import $t.fullName$;
}$
