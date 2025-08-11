package edu.njit.jerse.daikonplusplus.pp;

public enum ProgramPointKind {
  METHOD_ENTRY,
  METHOD_EXIT,
  CONSTRUCTOR_ENTRY,
  CONSTRUCTOR_EXIT,
  LOOP_HEADER_ENTRY,
  LOOP_HEADER_EXIT,
  LOOP_BODY_ENTRY,
  LOOP_BODY_EXIT,
  STATIC_BLOCK_ENTRY,
  STATIC_BLOCK_EXIT,
  LAMBDA_ENTRY,
  LAMBDA_EXIT,
  IF_CONDITION, // condition check only
  FIELD_READ, // field get
  FIELD_WRITE, // field set
  CUSTOM
}
