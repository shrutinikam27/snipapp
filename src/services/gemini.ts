export async function extractTextFromImage(base64Image: string, customPrompt?: string): Promise<string> {
  // Try to find the key in common locations
  let apiKey = (import.meta as any).env?.VITE_GEMINI_API_KEY || (process as any).env?.GEMINI_API_KEY;
  
  if (apiKey) {
    apiKey = apiKey.trim().replace(/^["']|["']$/g, '');
  }

  if (!apiKey || apiKey === "YOUR_GEMINI_API_KEY_HERE") {
    return "Error: Gemini API Key is missing. Please add VITE_GEMINI_API_KEY to your .env file and restart the dev server.";
  }

  // Optimized Discovery: Try most likely working combinations first
  const discoveryCombos = [
    { v: "v1beta", m: "gemini-1.5-flash-8b" },
    { v: "v1beta", m: "gemini-2.0-flash" },
    { v: "v1", m: "gemini-2.0-flash" },
    { v: "v1beta", m: "gemini-flash-latest" },
    { v: "v1beta", m: "gemini-1.5-flash" }
  ];

  console.log("GEMINI: Starting extraction with key:", apiKey.substring(0, 5) + "...");

  let lastError = "";

  for (const combo of discoveryCombos) {
    const { v, m: modelName } = combo;
    try {
      const url = `https://generativelanguage.googleapis.com/${v}/models/${modelName}:generateContent?key=${apiKey}`;
      console.log(`GEMINI: Trying ${v}/${modelName}...`);
      
      const response = await fetch(url, {
        method: 'POST',
        headers: { 
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          contents: [{
            parts: [
              { text: customPrompt || "Extract all text from this image exactly as it appears. Keep the original layout and line breaks." },
              { inlineData: { mimeType: "image/jpeg", data: base64Image.includes(',') ? base64Image.split(',')[1] : base64Image } }
            ]
          }]
        })
      });

      if (response.ok) {
        const result = await response.json();
        const text = result.candidates?.[0]?.content?.parts?.[0]?.text;
        if (text) {
          console.log("GEMINI: Extraction successful! Text length:", text.length);
          return text;
        }
        continue;
      }

      const errorData = await response.json();
      const msg = errorData?.error?.message || JSON.stringify(errorData);
      
      // Check for Quota/Rate Limit errors
      if (response.status === 429 || msg.toLowerCase().includes("quota") || msg.toLowerCase().includes("rate limit")) {
        return "Quota Exceeded: Your Gemini API limit has been reached. Please wait 60 seconds and try again.";
      }

      lastError = `${v}/${modelName}: ${msg}`;
      console.warn(`GEMINI: Error from ${v}/${modelName}:`, msg);
    } catch (e: any) {
      lastError = e.message;
      console.error(`GEMINI: Fetch failed for ${v}/${modelName}:`, e.message);
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
