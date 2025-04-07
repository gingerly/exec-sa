import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

private void onModelRunStopped() {
    mSendButton.setClickable(true);
    mSendButton.setImageResource(R.drawable.baseline_send_24);
    mSendButton.setOnClickListener(
        view -> {
          try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
          } catch (Exception e) {
            ETLogging.getInstance().log("Keyboard dismissal error: " + e.getMessage());
          }
          addSelectedImagesToChatThread(mSelectedImageUri);
          String finalPrompt;
          String rawPrompt = mEditTextMessage.getText().toString();
          if (ModelUtils.getModelCategory(
                  mCurrentSettingsFields.getModelType(), mCurrentSettingsFields.getBackendType())
              == ModelUtils.VISION_MODEL) {
            finalPrompt = mCurrentSettingsFields.getFormattedSystemAndUserPrompt(rawPrompt);
          } else {
            finalPrompt = getTotalFormattedPrompt(getConversationHistory(), rawPrompt);
          }
          // We store raw prompt into message adapter, because we don't want to show the extra
          // tokens from system prompt
          mMessageAdapter.add(new Message(rawPrompt, true, MessageType.TEXT, promptID));
          mMessageAdapter.notifyDataSetChanged();
          mEditTextMessage.setText("");
          mResultMessage = new Message("", false, MessageType.TEXT, promptID);
          mMessageAdapter.add(mResultMessage);
          // Scroll to bottom of the list
          mMessagesView.smoothScrollToPosition(mMessageAdapter.getCount() - 1);
          // After images are added to prompt and chat thread, we clear the imageURI list
          mSelectedImageUri = null;
          promptID++;
          
          Runnable runnable = new Runnable() {
            @Override
            public void run() {
              Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
              ETLogging.getInstance().log("Starting HTTP request to server");
              runOnUiThread(() -> onModelRunStarted());
              
              long generateStartTime = System.currentTimeMillis();
              
              // Create JSON request body based on request type
              JSONObject requestBody = new JSONObject();
              try {
                requestBody.put("prompt", finalPrompt);
                requestBody.put("max_tokens", (int)(finalPrompt.length() * 0.75) + 64);
                requestBody.put("model_type", mCurrentSettingsFields.getModelType().toString());
                
                // Add vision-specific data if needed
                if (ModelUtils.getModelCategory(
                        mCurrentSettingsFields.getModelType(), 
                        mCurrentSettingsFields.getBackendType()) == ModelUtils.VISION_MODEL && 
                    mSelectedImageUri != null && !mSelectedImageUri.isEmpty()) {
                  // You might need to implement a method to convert images to base64 or another format
                  // that can be sent over HTTP
                  requestBody.put("is_vision_model", true);
                  // This is just a placeholder - actual implementation would depend on how you want to handle images
                  // requestBody.put("images", getImageDataForRequest());
                }
                
                // Create OkHttp client with timeout
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
                
                // Create request
                MediaType JSON = MediaType.parse("application/json; charset=utf-8");
                RequestBody body = RequestBody.create(requestBody.toString(), JSON);
                Request request = new Request.Builder()
                    .url("https://your-server-api-endpoint.com/generate") // Replace with your server endpoint
                    .post(body)
                    .build();
                
                // Execute request asynchronously
                client.newCall(request).enqueue(new Callback() {
                  @Override
                  public void onFailure(Call call, IOException e) {
                    ETLogging.getInstance().log("Server request failed: " + e.getMessage());
                    runOnUiThread(() -> {
                      mResultMessage.appendText("Error: Failed to connect to server. " + e.getMessage());
                      mMessageAdapter.notifyDataSetChanged();
                      onModelRunStopped();
                    });
                  }
                  
                  @Override
                  public void onResponse(Call call, Response response) throws IOException {
                    try {
                      long generateDuration = System.currentTimeMillis() - generateStartTime;
                      mResultMessage.setTotalGenerationTime(generateDuration);
                      
                      if (!response.isSuccessful()) {
                        runOnUiThread(() -> {
                          mResultMessage.appendText("Error: Server returned status code " + response.code());
                          mMessageAdapter.notifyDataSetChanged();
                        });
                        return;
                      }
                      
                      String responseBody = response.body().string();
                      JSONObject jsonResponse = new JSONObject(responseBody);
                      
                      // Extract generated text from response
                      final String generatedText = jsonResponse.optString("generated_text", "");
                      final float tokensPerSec = (float)jsonResponse.optDouble("tokens_per_second", 0.0);
                      
                      // Update UI with response
                      runOnUiThread(() -> {
                        mResultMessage.appendText(generatedText);
                        mResultMessage.setTokensPerSecond(tokensPerSec);
                        mMessageAdapter.notifyDataSetChanged();
                      });
                      
                    } catch (Exception e) {
                      ETLogging.getInstance().log("Error processing response: " + e.getMessage());
                      runOnUiThread(() -> {
                        mResultMessage.appendText("Error processing server response: " + e.getMessage());
                        mMessageAdapter.notifyDataSetChanged();
                      });
                    } finally {
                      runOnUiThread(() -> onModelRunStopped());
                    }
                  }
                });
                
              } catch (Exception e) {
                ETLogging.getInstance().log("Error preparing request: " + e.getMessage());
                runOnUiThread(() -> {
                  mResultMessage.appendText("Error preparing request: " + e.getMessage());
                  mMessageAdapter.notifyDataSetChanged();
                  onModelRunStopped();
                });
              }
            }
          };
          
          executor.execute(runnable);
        });
    mMessageAdapter.notifyDataSetChanged();
}
