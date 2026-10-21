package com.example.hotel.chat;

public record ChatResult(boolean success, String message, String errorCode) {
  public static ChatResult success(String text) {
    return new ChatResult(true, text, null);
  }

  public static ChatResult failure(String code, String message) {
    return new ChatResult(false, message, code);
  }
}
