// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: entities.proto

package com.lynxanalytics.biggraph.graph_api.proto;

public final class Entities {
  private Entities() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  public interface VertexSetOrBuilder extends
      // @@protoc_insertion_point(interface_extends:proto.VertexSet)
      com.google.protobuf.MessageOrBuilder {

    /**
     * <code>repeated int64 ids = 1;</code>
     * @return A list containing the ids.
     */
    java.util.List<java.lang.Long> getIdsList();
    /**
     * <code>repeated int64 ids = 1;</code>
     * @return The count of ids.
     */
    int getIdsCount();
    /**
     * <code>repeated int64 ids = 1;</code>
     * @param index The index of the element to return.
     * @return The ids at the given index.
     */
    long getIds(int index);
  }
  /**
   * Protobuf type {@code proto.VertexSet}
   */
  public  static final class VertexSet extends
      com.google.protobuf.GeneratedMessageV3 implements
      // @@protoc_insertion_point(message_implements:proto.VertexSet)
      VertexSetOrBuilder {
  private static final long serialVersionUID = 0L;
    // Use VertexSet.newBuilder() to construct.
    private VertexSet(com.google.protobuf.GeneratedMessageV3.Builder<?> builder) {
      super(builder);
    }
    private VertexSet() {
      ids_ = emptyLongList();
    }

    @java.lang.Override
    @SuppressWarnings({"unused"})
    protected java.lang.Object newInstance(
        UnusedPrivateParameter unused) {
      return new VertexSet();
    }

    @java.lang.Override
    public final com.google.protobuf.UnknownFieldSet
    getUnknownFields() {
      return this.unknownFields;
    }
    private VertexSet(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      this();
      if (extensionRegistry == null) {
        throw new java.lang.NullPointerException();
      }
      int mutable_bitField0_ = 0;
      com.google.protobuf.UnknownFieldSet.Builder unknownFields =
          com.google.protobuf.UnknownFieldSet.newBuilder();
      try {
        boolean done = false;
        while (!done) {
          int tag = input.readTag();
          switch (tag) {
            case 0:
              done = true;
              break;
            case 8: {
              if (!((mutable_bitField0_ & 0x00000001) != 0)) {
                ids_ = newLongList();
                mutable_bitField0_ |= 0x00000001;
              }
              ids_.addLong(input.readInt64());
              break;
            }
            case 10: {
              int length = input.readRawVarint32();
              int limit = input.pushLimit(length);
              if (!((mutable_bitField0_ & 0x00000001) != 0) && input.getBytesUntilLimit() > 0) {
                ids_ = newLongList();
                mutable_bitField0_ |= 0x00000001;
              }
              while (input.getBytesUntilLimit() > 0) {
                ids_.addLong(input.readInt64());
              }
              input.popLimit(limit);
              break;
            }
            default: {
              if (!parseUnknownField(
                  input, unknownFields, extensionRegistry, tag)) {
                done = true;
              }
              break;
            }
          }
        }
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        throw e.setUnfinishedMessage(this);
      } catch (java.io.IOException e) {
        throw new com.google.protobuf.InvalidProtocolBufferException(
            e).setUnfinishedMessage(this);
      } finally {
        if (((mutable_bitField0_ & 0x00000001) != 0)) {
          ids_.makeImmutable(); // C
        }
        this.unknownFields = unknownFields.build();
        makeExtensionsImmutable();
      }
    }
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return com.lynxanalytics.biggraph.graph_api.proto.Entities.internal_static_proto_VertexSet_descriptor;
    }

    @java.lang.Override
    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return com.lynxanalytics.biggraph.graph_api.proto.Entities.internal_static_proto_VertexSet_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.class, com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.Builder.class);
    }

    public static final int IDS_FIELD_NUMBER = 1;
    private com.google.protobuf.Internal.LongList ids_;
    /**
     * <code>repeated int64 ids = 1;</code>
     * @return A list containing the ids.
     */
    public java.util.List<java.lang.Long>
        getIdsList() {
      return ids_;
    }
    /**
     * <code>repeated int64 ids = 1;</code>
     * @return The count of ids.
     */
    public int getIdsCount() {
      return ids_.size();
    }
    /**
     * <code>repeated int64 ids = 1;</code>
     * @param index The index of the element to return.
     * @return The ids at the given index.
     */
    public long getIds(int index) {
      return ids_.getLong(index);
    }
    private int idsMemoizedSerializedSize = -1;

    private byte memoizedIsInitialized = -1;
    @java.lang.Override
    public final boolean isInitialized() {
      byte isInitialized = memoizedIsInitialized;
      if (isInitialized == 1) return true;
      if (isInitialized == 0) return false;

      memoizedIsInitialized = 1;
      return true;
    }

    @java.lang.Override
    public void writeTo(com.google.protobuf.CodedOutputStream output)
                        throws java.io.IOException {
      getSerializedSize();
      if (getIdsList().size() > 0) {
        output.writeUInt32NoTag(10);
        output.writeUInt32NoTag(idsMemoizedSerializedSize);
      }
      for (int i = 0; i < ids_.size(); i++) {
        output.writeInt64NoTag(ids_.getLong(i));
      }
      unknownFields.writeTo(output);
    }

    @java.lang.Override
    public int getSerializedSize() {
      int size = memoizedSize;
      if (size != -1) return size;

      size = 0;
      {
        int dataSize = 0;
        for (int i = 0; i < ids_.size(); i++) {
          dataSize += com.google.protobuf.CodedOutputStream
            .computeInt64SizeNoTag(ids_.getLong(i));
        }
        size += dataSize;
        if (!getIdsList().isEmpty()) {
          size += 1;
          size += com.google.protobuf.CodedOutputStream
              .computeInt32SizeNoTag(dataSize);
        }
        idsMemoizedSerializedSize = dataSize;
      }
      size += unknownFields.getSerializedSize();
      memoizedSize = size;
      return size;
    }

    @java.lang.Override
    public boolean equals(final java.lang.Object obj) {
      if (obj == this) {
       return true;
      }
      if (!(obj instanceof com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet)) {
        return super.equals(obj);
      }
      com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet other = (com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet) obj;

      if (!getIdsList()
          .equals(other.getIdsList())) return false;
      if (!unknownFields.equals(other.unknownFields)) return false;
      return true;
    }

    @java.lang.Override
    public int hashCode() {
      if (memoizedHashCode != 0) {
        return memoizedHashCode;
      }
      int hash = 41;
      hash = (19 * hash) + getDescriptor().hashCode();
      if (getIdsCount() > 0) {
        hash = (37 * hash) + IDS_FIELD_NUMBER;
        hash = (53 * hash) + getIdsList().hashCode();
      }
      hash = (29 * hash) + unknownFields.hashCode();
      memoizedHashCode = hash;
      return hash;
    }

    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        java.nio.ByteBuffer data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        java.nio.ByteBuffer data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        com.google.protobuf.ByteString data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        com.google.protobuf.ByteString data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(byte[] data)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        byte[] data,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
      return PARSER.parseFrom(data, extensionRegistry);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseDelimitedFrom(java.io.InputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseDelimitedFrom(
        java.io.InputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        com.google.protobuf.CodedInputStream input)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input);
    }
    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parseFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      return com.google.protobuf.GeneratedMessageV3
          .parseWithIOException(PARSER, input, extensionRegistry);
    }

    @java.lang.Override
    public Builder newBuilderForType() { return newBuilder(); }
    public static Builder newBuilder() {
      return DEFAULT_INSTANCE.toBuilder();
    }
    public static Builder newBuilder(com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet prototype) {
      return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
    }
    @java.lang.Override
    public Builder toBuilder() {
      return this == DEFAULT_INSTANCE
          ? new Builder() : new Builder().mergeFrom(this);
    }

    @java.lang.Override
    protected Builder newBuilderForType(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      Builder builder = new Builder(parent);
      return builder;
    }
    /**
     * Protobuf type {@code proto.VertexSet}
     */
    public static final class Builder extends
        com.google.protobuf.GeneratedMessageV3.Builder<Builder> implements
        // @@protoc_insertion_point(builder_implements:proto.VertexSet)
        com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSetOrBuilder {
      public static final com.google.protobuf.Descriptors.Descriptor
          getDescriptor() {
        return com.lynxanalytics.biggraph.graph_api.proto.Entities.internal_static_proto_VertexSet_descriptor;
      }

      @java.lang.Override
      protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
          internalGetFieldAccessorTable() {
        return com.lynxanalytics.biggraph.graph_api.proto.Entities.internal_static_proto_VertexSet_fieldAccessorTable
            .ensureFieldAccessorsInitialized(
                com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.class, com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.Builder.class);
      }

      // Construct using com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.newBuilder()
      private Builder() {
        maybeForceBuilderInitialization();
      }

      private Builder(
          com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
        super(parent);
        maybeForceBuilderInitialization();
      }
      private void maybeForceBuilderInitialization() {
        if (com.google.protobuf.GeneratedMessageV3
                .alwaysUseFieldBuilders) {
        }
      }
      @java.lang.Override
      public Builder clear() {
        super.clear();
        ids_ = emptyLongList();
        bitField0_ = (bitField0_ & ~0x00000001);
        return this;
      }

      @java.lang.Override
      public com.google.protobuf.Descriptors.Descriptor
          getDescriptorForType() {
        return com.lynxanalytics.biggraph.graph_api.proto.Entities.internal_static_proto_VertexSet_descriptor;
      }

      @java.lang.Override
      public com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet getDefaultInstanceForType() {
        return com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.getDefaultInstance();
      }

      @java.lang.Override
      public com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet build() {
        com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet result = buildPartial();
        if (!result.isInitialized()) {
          throw newUninitializedMessageException(result);
        }
        return result;
      }

      @java.lang.Override
      public com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet buildPartial() {
        com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet result = new com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet(this);
        int from_bitField0_ = bitField0_;
        if (((bitField0_ & 0x00000001) != 0)) {
          ids_.makeImmutable();
          bitField0_ = (bitField0_ & ~0x00000001);
        }
        result.ids_ = ids_;
        onBuilt();
        return result;
      }

      @java.lang.Override
      public Builder clone() {
        return super.clone();
      }
      @java.lang.Override
      public Builder setField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.setField(field, value);
      }
      @java.lang.Override
      public Builder clearField(
          com.google.protobuf.Descriptors.FieldDescriptor field) {
        return super.clearField(field);
      }
      @java.lang.Override
      public Builder clearOneof(
          com.google.protobuf.Descriptors.OneofDescriptor oneof) {
        return super.clearOneof(oneof);
      }
      @java.lang.Override
      public Builder setRepeatedField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          int index, java.lang.Object value) {
        return super.setRepeatedField(field, index, value);
      }
      @java.lang.Override
      public Builder addRepeatedField(
          com.google.protobuf.Descriptors.FieldDescriptor field,
          java.lang.Object value) {
        return super.addRepeatedField(field, value);
      }
      @java.lang.Override
      public Builder mergeFrom(com.google.protobuf.Message other) {
        if (other instanceof com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet) {
          return mergeFrom((com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet)other);
        } else {
          super.mergeFrom(other);
          return this;
        }
      }

      public Builder mergeFrom(com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet other) {
        if (other == com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet.getDefaultInstance()) return this;
        if (!other.ids_.isEmpty()) {
          if (ids_.isEmpty()) {
            ids_ = other.ids_;
            bitField0_ = (bitField0_ & ~0x00000001);
          } else {
            ensureIdsIsMutable();
            ids_.addAll(other.ids_);
          }
          onChanged();
        }
        this.mergeUnknownFields(other.unknownFields);
        onChanged();
        return this;
      }

      @java.lang.Override
      public final boolean isInitialized() {
        return true;
      }

      @java.lang.Override
      public Builder mergeFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws java.io.IOException {
        com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet parsedMessage = null;
        try {
          parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
          parsedMessage = (com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet) e.getUnfinishedMessage();
          throw e.unwrapIOException();
        } finally {
          if (parsedMessage != null) {
            mergeFrom(parsedMessage);
          }
        }
        return this;
      }
      private int bitField0_;

      private com.google.protobuf.Internal.LongList ids_ = emptyLongList();
      private void ensureIdsIsMutable() {
        if (!((bitField0_ & 0x00000001) != 0)) {
          ids_ = mutableCopy(ids_);
          bitField0_ |= 0x00000001;
         }
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @return A list containing the ids.
       */
      public java.util.List<java.lang.Long>
          getIdsList() {
        return ((bitField0_ & 0x00000001) != 0) ?
                 java.util.Collections.unmodifiableList(ids_) : ids_;
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @return The count of ids.
       */
      public int getIdsCount() {
        return ids_.size();
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @param index The index of the element to return.
       * @return The ids at the given index.
       */
      public long getIds(int index) {
        return ids_.getLong(index);
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @param index The index to set the value at.
       * @param value The ids to set.
       * @return This builder for chaining.
       */
      public Builder setIds(
          int index, long value) {
        ensureIdsIsMutable();
        ids_.setLong(index, value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @param value The ids to add.
       * @return This builder for chaining.
       */
      public Builder addIds(long value) {
        ensureIdsIsMutable();
        ids_.addLong(value);
        onChanged();
        return this;
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @param values The ids to add.
       * @return This builder for chaining.
       */
      public Builder addAllIds(
          java.lang.Iterable<? extends java.lang.Long> values) {
        ensureIdsIsMutable();
        com.google.protobuf.AbstractMessageLite.Builder.addAll(
            values, ids_);
        onChanged();
        return this;
      }
      /**
       * <code>repeated int64 ids = 1;</code>
       * @return This builder for chaining.
       */
      public Builder clearIds() {
        ids_ = emptyLongList();
        bitField0_ = (bitField0_ & ~0x00000001);
        onChanged();
        return this;
      }
      @java.lang.Override
      public final Builder setUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return super.setUnknownFields(unknownFields);
      }

      @java.lang.Override
      public final Builder mergeUnknownFields(
          final com.google.protobuf.UnknownFieldSet unknownFields) {
        return super.mergeUnknownFields(unknownFields);
      }


      // @@protoc_insertion_point(builder_scope:proto.VertexSet)
    }

    // @@protoc_insertion_point(class_scope:proto.VertexSet)
    private static final com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet DEFAULT_INSTANCE;
    static {
      DEFAULT_INSTANCE = new com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet();
    }

    public static com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet getDefaultInstance() {
      return DEFAULT_INSTANCE;
    }

    private static final com.google.protobuf.Parser<VertexSet>
        PARSER = new com.google.protobuf.AbstractParser<VertexSet>() {
      @java.lang.Override
      public VertexSet parsePartialFrom(
          com.google.protobuf.CodedInputStream input,
          com.google.protobuf.ExtensionRegistryLite extensionRegistry)
          throws com.google.protobuf.InvalidProtocolBufferException {
        return new VertexSet(input, extensionRegistry);
      }
    };

    public static com.google.protobuf.Parser<VertexSet> parser() {
      return PARSER;
    }

    @java.lang.Override
    public com.google.protobuf.Parser<VertexSet> getParserForType() {
      return PARSER;
    }

    @java.lang.Override
    public com.lynxanalytics.biggraph.graph_api.proto.Entities.VertexSet getDefaultInstanceForType() {
      return DEFAULT_INSTANCE;
    }

  }

  private static final com.google.protobuf.Descriptors.Descriptor
    internal_static_proto_VertexSet_descriptor;
  private static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_proto_VertexSet_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\016entities.proto\022\005proto\"\030\n\tVertexSet\022\013\n\003" +
      "ids\030\001 \003(\003B,\n*com.lynxanalytics.biggraph." +
      "graph_api.protob\006proto3"
    };
    descriptor = com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        });
    internal_static_proto_VertexSet_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_proto_VertexSet_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_proto_VertexSet_descriptor,
        new java.lang.String[] { "Ids", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
