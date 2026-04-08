export async function extractTextFromImage(base64Image: string, customPrompt?: string): Promise<string> {
  let apiKey = (import.meta as any).env.VITE_GEMINI_API_KEY;
  
  if (apiKey) {
    apiKey = apiKey.trim().replace(/^["']|["']$/g, '');
  }

  if (!apiKey) return "Error: API Key missing.";

  // Discovery phase: Find a working model
  const apiVersions = ["v1beta", "v1"];
  const candidateModels = [
    "gemini-2.5-flash",
    "gemini-2.5-pro",
    "gemini-2.0-flash",
    "gemini-flash-latest",
    "gemini-3.1-flash-lite-preview",
    "gemini-1.5-flash", // Legacy fallback
    "gemini-1.5-pro"
  ];

  let lastError = "";

  for (const v of apiVersions) {
    for (const modelName of candidateModels) {
      try {
        const url = `https://generativelanguage.googleapis.com/${v}/models/${modelName}:generateContent?key=${apiKey}`;
        
        const response = await fetch(url, {
          method: 'POST',
          headers: { 
            'Content-Type': 'application/json',
            'x-goog-api-key': apiKey // redundant but safe
          },
          body: JSON.stringify({
            contents: [{
              parts: [
                { text: customPrompt || "Extract all text from image exactly. Maintain formatting if possible." },
                { inlineData: { mimeType: "image/jpeg", data: base64Image.includes(',') ? base64Image.split(',')[1] : base64Image } }
              ]
            }]
          })
        });

        if (response.ok) {
          const result = await response.json();
          const text = result.candidates?.[0]?.content?.parts?.[0]?.text;
          if (text) return text;
          continue;
        }

        const errorData = await response.json();
        lastError = errorData?.error?.message || "Unknown error";
        console.warn(`Failed ${v}/${modelName}:`, lastError);
      } catch (e: any) {
        lastError = e.message;
      }
    }
  }

  // If ALL models fail, try to LIST accessible models to find the real problem
  try {
    const listUrl = `https://generativelanguage.googleapis.com/v1beta/models?key=${apiKey}`;
    const listRes = await fetch(listUrl);
    if (listRes.ok) {
      const listData = await listRes.json();
      const names = listData.models?.map((m: any) => m.name.split('/').pop()).join(", ") || "none";
      return `AI Connection Error: Your Key doesn't see any Gemini Vision models. Available models for your key: ${names}. Check Google AI Studio.`;
    } else {
        const listErr = await listRes.json();
        return `API Configuration Error: ${listErr.error?.message || "Invalid Key"}. Please verify your API Key in Google AI Studio. Status: ${listRes.status}`;
    }
  } catch (secondaryErr: any) {
    return `AI Error: No models found and listing failed (${secondaryErr.message})`;
  }
}
