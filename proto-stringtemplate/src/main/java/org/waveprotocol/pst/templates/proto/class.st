public $if(m.inner)$static$endif$ final class $m.javaType$
    implements $m.name$,
    // Note: fully-qualified path is required for GsonSerializable and ProtoWrapper.
    // An import of it is not resolved correctly from inner classes.
    // This appears to be a javac bug.  The Eclipse compiler handles it fine.
    org.waveprotocol.wave.communication.gson.GsonSerializable,
    org.waveprotocol.wave.communication.proto.ProtoWrapper<$m.protoType$> {

  $m.nestedMessages: {nested|$class(m=nested)$}$

  private $m.protoType$ proto = null;
  private $m.protoType$.Builder protoBuilder = $m.protoType$.newBuilder();

  public $m.javaType$() {
  }

  public $m.javaType$($m.protoType$ proto) {
    this.proto = proto;
  }

  public $m.javaType$($m.name$ message) {
    copyFrom(message);
  }

  @Override
  public $m.protoType$ getPB() {
    lazilySwitchToProto();
    return proto;
  }

  @Override
  public void setPB($m.protoType$ proto) {
    this.proto = proto;
    this.protoBuilder = null;
  }

  @Override
  public void copyFrom($m.name$ message) {
    $m.fields: {f|$copyField(f=f, target="message")$}$
  }

  $m.fields: {f|$beans(m=m, f=f)$}$

  $m.referencedMessages: {t|$messageHelpers(t=t)$}$
  $m.referencedEnums: {t|$enumHelpers(t=t)$}$

  private void lazilySwitchToProto() {
    if (proto == null) {
      proto = protoBuilder.build();
      protoBuilder = null;
    }
  }

  private void lazilySwitchToProtoBuilder() {
    if (protoBuilder == null) {
      protoBuilder = (proto == null)
          ? $m.protoType$.newBuilder()
          : $m.protoType$.newBuilder(proto);
      proto = null;
    }
  }

  private void invalidateAll() {
    proto = null;
    protoBuilder = $m.protoType$.newBuilder();
  }

  @Override
  public JsonElement toGson(RawStringData raw, Gson gson) {
    JsonObject json = new JsonObject();
    $m.fields: {f|$toGsonField(f=f, target="json")$}$
    return json;
  }

  @Override
  public void fromGson(JsonElement json, Gson gson, RawStringData raw) throws GsonException {
    JsonObject jsonObject = json.getAsJsonObject();
    // NOTE: always check with has(...) as the json might not have all required
    // fields set; however these (obviously) will need to be set by other means
    // before accessing this object.
    invalidateAll();
    $m.fields: {f|$fromGsonField(f=f, target="jsonObject")$}$
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    } else if (o instanceof $m.javaType$) {
      return getPB().equals((($m.javaType$) o).getPB());
    } else {
      return false;
    }
  }

  @Override
  public boolean isEqualTo(Object o) {
    if (equals(o)) {
      return true;
    } else if (o instanceof $m.name$) {
      return $m.name$Util.isEqual(this, ($m.name$) o);
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    return getPB().hashCode();
  }

  @Override
  public String toString() {
    return getPB().toString();
  }
}
