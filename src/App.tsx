import React, { useState, useRef, useEffect } from 'react';
import ReactCrop, { type Crop, PixelCrop } from 'react-image-crop';
import { Camera, Image as ImageIcon, Check, Copy, RefreshCw, Loader2, Scissors, ChevronLeft, MonitorUp, Smartphone, Globe, Layout, History, Trash2, Clock, Calendar } from 'lucide-react';
import { extractTextFromImage } from './services/gemini';
import { Camera as CapCamera, CameraResultType, CameraSource } from '@capacitor/camera';
import { Clipboard } from '@capacitor/clipboard';
import { motion, AnimatePresence } from 'motion/react';
import { registerPlugin } from '@capacitor/core';

export interface ScreenCapturePlugin {
  startCapture(): Promise<{ value: string; width?: number; height?: number }>;
  captureInsideApp(): Promise<{ value: string; width?: number; height?: number }>;
  enableFloating(): Promise<void>;
  disableFloating(): Promise<void>;
  checkPendingCapture(): Promise<{ value?: string; width?: number; height?: number }>;
  addListener(eventName: 'onCaptureResult', listenerFunc: (data: { value: string; width?: number; height?: number }) => void): any;
}
const ScreenCapture = registerPlugin<ScreenCapturePlugin>('ScreenCapture');

export interface HistoryItem {
  id: string;
  image: string;
  text: string;
  timestamp: number;
}

// Detect if running inside Capacitor (native mobile app)
const isNativeMobile = (window as any).Capacitor?.isNativePlatform();

export default function App() {
  const [appState, setAppState] = useState<'upload' | 'crop' | 'loading' | 'result' | 'history'>('upload');
  const [imgSrc, setImgSrc] = useState<string | null>(null);
  const [crop, setCrop] = useState<Crop>({
    unit: '%',
    x: 10,
    y: 10,
    width: 80,
    height: 80
  });
  const [completedCrop, setCompletedCrop] = useState<PixelCrop>();
  const [extractedText, setExtractedText] = useState("");
  const [copied, setCopied] = useState(false);
  const [showCaptureOptions, setShowCaptureOptions] = useState(false);
  const [history, setHistory] = useState<HistoryItem[]>([]);
  const [isFloatingEnabled, setIsFloatingEnabled] = useState(false);
  
  // Load history from localStorage on start
  useEffect(() => {
    const saved = localStorage.getItem('snip_history');
    if (saved) {
      try {
        setHistory(JSON.parse(saved));
      } catch (e) {
        console.error("Failed to load history", e);
      }
    }
  }, []);

  // Sync history with localStorage
  useEffect(() => {
    localStorage.setItem('snip_history', JSON.stringify(history));
  }, [history]);

  // Define at component level for UI access
  const checkPending = async () => {
    try {
      if (!isNativeMobile) return;
      const pending = await ScreenCapture.checkPendingCapture();
      if (pending && pending.value) {
        console.log("APP: Found pending capture! Size:", Math.round(pending.value.length / 1024), "KB");
        loadIntoExtractPage(pending.value);
        return true;
      }
    } catch (e) {
      console.error("APP: checking pending failed", e);
    }
    return false;
  };

  const registerListener = async () => {
    try {
      return await (ScreenCapture as any).addListener('onCaptureResult', (data: { value: string; width?: number; height?: number }) => {
        if (data && data.value) {
          console.log("APP: Received capture via listener! Type:", data.value.substring(0, 20), "Size:", Math.round(data.value.length / 1024), "KB");
          loadIntoExtractPage(data.value);
        } else {
          console.warn("APP: Received empty capture via listener");
        }
      });
    } catch (err) {
      console.error("APP: Failed to register capture listener:", err);
      return null;
    }
  };

  // Register capture listener and recovery mechanism
  useEffect(() => {
    let listenerHandle: any = null;
    
    const init = async () => {
      listenerHandle = await registerListener();
      checkPending(); // check immediately
    };
    
    init();

    // Check whenever the app comes back to the foreground
    const handleVisibilityChange = async () => {
      if (document.visibilityState === 'visible') {
        const found = await checkPending();
        if (!found) {
          setTimeout(() => checkPending(), 500);
          setTimeout(() => checkPending(), 1500);
          setTimeout(() => checkPending(), 3000);
        }
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      if (listenerHandle && typeof listenerHandle.remove === 'function') {
        listenerHandle.remove();
      }
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, []);

  const onImageLoad = (e: React.SyntheticEvent<HTMLImageElement>) => {
    const { width, height } = e.currentTarget;
    const initialCrop: PixelCrop = {
      unit: 'px',
      x: 0,
      y: 0,
      width: width,
      height: height
    };
    setCompletedCrop(initialCrop);
  };

  const loadIntoExtractPage = (imageSrc: string, skipCrop = true) => {
    console.log("APP: loadIntoExtractPage called. skipCrop:", skipCrop);
    if (skipCrop) {
      processImageForOCR(imageSrc);
    } else {
      setImgSrc(imageSrc);
      setAppState('crop');
    }
  };


  const isProcessingRef = useRef(false);
  const [isExtracting, setIsExtracting] = useState(false);

  const processImageForOCR = async (imageSrc: string) => {
    if (isProcessingRef.current) {
        console.log("APP: Extraction already in progress (ref lock), skipping.");
        return;
    }
    console.log("APP: processImageForOCR starting...");
    if (!imageSrc) {
      console.warn("APP: Empty image source passed to OCR");
      return;
    }
    
    isProcessingRef.current = true;
    setIsExtracting(true);
    setImgSrc(imageSrc);
    setAppState('loading');
    
    try {
      const base64Data = imageSrc.includes(',') ? imageSrc.split(',')[1] : imageSrc;
      console.log("APP: Sending to extraction service...");
      
      // Powerful prompt for perfect extraction
      const prompt = "Extract all text from this image exactly as written. " +
                     "Maintain vertical and horizontal layout if possible. " +
                     "Do not summarize. Do not add notes or preamble.";
      
      const text = await extractTextFromImage(base64Data, prompt);
      console.log("APP: Extraction successful, text length:", text.length);
      setExtractedText(text);
      setAppState('result');
      
      // Save TO HISTORY if OCR was successful
      if (text && text.trim().length > 0 && !text.startsWith("API Error") && !text.startsWith("AI Error")) {
        const newItem: HistoryItem = {
          id: Date.now().toString(),
          image: imageSrc,
          text: text,
          timestamp: Date.now()
        };
        setHistory(prev => [newItem, ...prev].slice(0, 50)); // Keep last 50 items
        
        // Auto-copy using robust Capacitor plugin
        try {
          await Clipboard.write({
            string: text
          });
          setCopied(true);
          setTimeout(() => setCopied(false), 2000);
        } catch (copyErr) {
          console.error("APP: Auto-copy failed. Falling back to navigator...", copyErr);
          try {
            await navigator.clipboard.writeText(text);
          } catch (navErr) {}
        }
      }
    } catch (err: any) {
      console.error("OCR failed:", err);
      setExtractedText(err?.message ? `Failed to process image: ${err.message}` : "Failed to process image.");
      setAppState('result');
    } finally {
      setIsExtracting(false);
      isProcessingRef.current = false;
    }
  };
  
  const imgRef = useRef<HTMLImageElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const resetApp = () => {
    setAppState('upload');
    setImgSrc(null);
    setExtractedText("");
    setCopied(false);
  };

  const onSelectFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files.length > 0) {
      const reader = new FileReader();
      reader.onload = () => {
        const url = reader.result?.toString() || null;
        if (url) {
          // Pre-load image to get dimensions immediately
          const tempImg = new Image();
          tempImg.onload = () => {
            setCompletedCrop({
              unit: 'px',
              x: tempImg.width * 0.1,
              y: tempImg.height * 0.1,
              width: tempImg.width * 0.8,
              height: tempImg.height * 0.8
            });
            setImgSrc(url);
            setAppState('crop');
          };
          tempImg.src = url;
        }
      };
      reader.readAsDataURL(e.target.files[0]);
    }
  };

  const takePhoto = async () => {
    try {
      const image = await CapCamera.getPhoto({
        quality: 90,
        allowEditing: false,
        resultType: CameraResultType.DataUrl,
        source: CameraSource.Camera
      }) as any;
      
      if (image.dataUrl) {
        // Pre-calculate crop from camera result
        if (image.width && image.height) {
          setCompletedCrop({
            unit: 'px',
            x: image.width * 0.1,
            y: image.height * 0.1,
            width: image.width * 0.8,
            height: image.height * 0.8
          });
        }
        setImgSrc(image.dataUrl);
        setAppState('crop');
      }
    } catch (e) {
      console.error("Camera error:", e);
    }
  };

  const handleExtractText = async () => {
    if (!completedCrop || !imgRef.current) return;
    try {
      const base64Data = await getCroppedImg(imgRef.current, completedCrop);
      processImageForOCR(`data:image/jpeg;base64,${base64Data}`);
    } catch (error) {
      console.error(error);
      setExtractedText('Failed to extract text.');
      setAppState('result');
    }
  };

  const copyToClipboard = async (text?: string) => {
    const textToCopy = text || extractedText;
    try {
      await Clipboard.write({
        string: textToCopy
      });
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('APP: Capacitor copy failed!', err);
      // navigator fallback
      try {
        await navigator.clipboard.writeText(textToCopy);
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      } catch (navErr) {
        alert("Copy failed. Please select text manually.");
      }
    }
  };

  const deleteHistoryItem = (id: string) => {
    setHistory(prev => prev.filter(item => item.id !== id));
  };

  const clearHistory = () => {
    if (confirm("Clear all snip history?")) {
      setHistory([]);
    }
  };

  return (
    <div className="min-h-screen bg-[#0A0A0A] text-white flex flex-col font-sans selection:bg-indigo-500/30">
      {/* Header */}
      <header className="p-4 flex items-center justify-between border-b border-white/5 bg-black/20 backdrop-blur-md sticky top-0 z-50">
        <div className="flex items-center gap-2 cursor-pointer" onClick={resetApp}>
          <div className="w-8 h-8 bg-indigo-600 rounded-lg flex items-center justify-center shadow-lg shadow-indigo-600/20">
            <Scissors className="w-5 h-5" />
          </div>
          <h1 className="text-xl font-bold tracking-tight">SnipText</h1>
        </div>
        
        <div className="flex items-center gap-2">
          {appState === 'upload' && history.length > 0 && (
             <button 
              onClick={() => setAppState('history')}
              className="p-2 rounded-full bg-white/5 hover:bg-white/10 transition-colors"
            >
              <History className="w-5 h-5" />
            </button>
          )}

          {isNativeMobile && (
          <button 
            onClick={async () => {
              if (isFloatingEnabled) {
                await ScreenCapture.disableFloating();
                setIsFloatingEnabled(false);
              } else {
                await ScreenCapture.enableFloating();
                setIsFloatingEnabled(true);
              }
            }}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-full transition-all text-xs font-medium border ${
              isFloatingEnabled 
              ? "bg-indigo-600 text-white border-indigo-500 shadow-lg shadow-indigo-600/30" 
              : "bg-white/5 text-gray-400 border-white/10"
            }`}
          >
            <MonitorUp className="w-3.5 h-3.5" />
            {isFloatingEnabled ? "Floating ON" : "Bubble"}
          </button>
        )}
        </div>
      </header>

      <main className="flex-1 flex flex-col">
        <AnimatePresence mode="wait">
          {appState === 'upload' && (
            <motion.div 
              key="upload"
              initial={{ opacity: 0, y: 10 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -10 }}
              className="flex-1 flex flex-col items-center justify-center p-6 space-y-8"
            >
              <div className="text-center space-y-3">
                <h2 className="text-4xl font-bold bg-gradient-to-br from-white to-gray-500 bg-clip-text text-transparent">
                  Instant Extract
                </h2>
                <p className="text-gray-400 max-w-xs mx-auto leading-relaxed">
                  Turn your screen or photos into copyable text in seconds.
                </p>
              </div>

              <div className="grid grid-cols-2 gap-4 w-full max-w-sm">
                <button 
                  onClick={() => setShowCaptureOptions(true)}
                  className="col-span-2 group relative flex flex-col items-center gap-4 p-8 rounded-3xl bg-indigo-600 hover:bg-indigo-500 transition-all shadow-xl shadow-indigo-900/20 active:scale-[0.98]"
                >
                  <div className="w-16 h-16 rounded-2xl bg-white/10 flex items-center justify-center group-hover:scale-110 transition-transform">
                    <Layout className="w-8 h-8" />
                  </div>
                  <div className="text-center">
                    <div className="text-lg font-bold">Snip Screen</div>
                    <div className="text-indigo-100/60 text-sm">Capture from any app</div>
                  </div>
                </button>

                <button 
                  onClick={takePhoto}
                  className="group flex flex-col items-center gap-4 p-6 rounded-3xl bg-white/5 hover:bg-white/10 transition-all border border-white/10 active:scale-[0.98]"
                >
                  <div className="w-12 h-12 rounded-xl bg-white/5 flex items-center justify-center group-hover:scale-110 transition-transform">
                    <Camera className="w-6 h-6 text-gray-300" />
                  </div>
                  <div className="font-bold text-sm">Take Photo</div>
                </button>

                <button 
                  onClick={() => fileInputRef.current?.click()}
                  className="group flex flex-col items-center gap-4 p-6 rounded-3xl bg-white/5 hover:bg-white/10 transition-all border border-white/10 active:scale-[0.98]"
                >
                  <div className="w-12 h-12 rounded-xl bg-white/5 flex items-center justify-center group-hover:scale-110 transition-transform">
                    <ImageIcon className="w-6 h-6 text-gray-300" />
                  </div>
                  <div className="font-bold text-sm">Import</div>
                </button>
              </div>

              <input 
                type="file" 
                ref={fileInputRef} 
                onChange={onSelectFile} 
                accept="image/*" 
                className="hidden" 
              />
            </motion.div>
          )}

          {appState === 'history' && (
            <motion.div 
              key="history"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex-1 flex flex-col p-4 animate-in fade-in"
            >
              <div className="flex items-center justify-between mb-6">
                <button onClick={resetApp} className="flex items-center gap-2 text-gray-400 hover:text-white transition-colors">
                  <ChevronLeft className="w-5 h-5" />
                  <span className="font-medium">Recent Snips</span>
                </button>
                <button onClick={clearHistory} className="text-red-400 p-2 hover:bg-red-400/10 rounded-full transition-all">
                  <Trash2 className="w-5 h-5" />
                </button>
              </div>

              <div className="flex-1 space-y-4 overflow-y-auto pb-20">
                {history.map((item) => (
                  <div 
                    key={item.id} 
                    onClick={() => {
                        setImgSrc(item.image);
                        setExtractedText(item.text);
                        setAppState('result');
                    }}
                    className="bg-white/5 rounded-2xl border border-white/5 overflow-hidden group cursor-pointer hover:bg-white/10 transition-all active:scale-[0.98]"
                  >
                    <div className="flex p-3 gap-3">
                      <div className="w-20 h-20 rounded-lg overflow-hidden flex-shrink-0 bg-black/40 border border-white/10">
                        <img src={item.image} alt="Snip" className="w-full h-full object-cover" />
                      </div>
                      <div className="flex-1 min-w-0 pr-2">
                        <div className="flex items-center gap-2 text-[10px] text-gray-500 mb-1">
                          <Calendar className="w-3 h-3" />
                          {new Date(item.timestamp).toLocaleDateString()}
                          <span className="opacity-30">|</span>
                          <Clock className="w-3 h-3" />
                          {new Date(item.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </div>
                        <p className="text-sm text-gray-300 line-clamp-3 leading-relaxed">
                          {item.text}
                        </p>
                      </div>
                      <div className="flex flex-col gap-2">
                        <button 
                          onClick={(e) => {
                            e.stopPropagation();
                            copyToClipboard(item.text);
                          }}
                          className="p-2 rounded-lg bg-white/5 hover:bg-white/10 text-gray-400 hover:text-indigo-400 transition-all"
                        >
                          <Copy className="w-4 h-4" />
                        </button>
                        <button 
                          onClick={(e) => {
                            e.stopPropagation();
                            deleteHistoryItem(item.id);
                          }}
                          className="p-2 rounded-lg bg-white/5 hover:bg-red-500/20 text-gray-400 hover:text-red-400 transition-all"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </motion.div>
          )}

          {showCaptureOptions && (
            <div className="fixed inset-0 z-[100] flex items-center justify-center p-6 bg-black/80 backdrop-blur-sm">
              <motion.div 
                initial={{ opacity: 0, scale: 0.9 }}
                animate={{ opacity: 1, scale: 1 }}
                className="w-full max-w-sm bg-[#1A1A1A] rounded-3xl overflow-hidden border border-white/10 shadow-2xl"
              >
                <div className="p-6 text-center border-b border-white/5">
                  <h3 className="text-xl font-bold">Capture Mode</h3>
                  <p className="text-sm text-gray-400 mt-1">Choose how you want to snip</p>
                </div>
                
                <div className="p-4 grid grid-cols-1 gap-3">
                  <button 
                    onClick={async () => {
                      setShowCaptureOptions(false);
                      if (isNativeMobile) {
                        try {
                          const result = await ScreenCapture.startCapture() as any;
                          if (result && result.value) {
                            loadIntoExtractPage(result.value);
                          }
                        } catch (err: any) {
                          console.error("Entire screen capture failed:", err);
                        }
                      } else {
                        // Web Fallback for browsers
                        try {
                          const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
                          const video = document.createElement('video');
                          video.srcObject = stream;
                          video.play();
                          
                          video.onloadedmetadata = () => {
                            setTimeout(() => {
                              const canvas = document.createElement('canvas');
                              canvas.width = video.videoWidth;
                              canvas.height = video.videoHeight;
                              const ctx = canvas.getContext('2d');
                              ctx?.drawImage(video, 0, 0);
                              const base64 = canvas.toDataURL('image/jpeg');
                              loadIntoExtractPage(base64, false); // Web capture needs cropping
                              stream.getTracks().forEach(track => track.stop());
                            }, 500);
                          };
                        } catch (err) {
                          console.error("Web capture failed:", err);
                          alert("Screen capture is not supported or was cancelled.");
                        }
                      }
                    }}
                    className="flex items-center gap-4 p-4 rounded-2xl bg-indigo-600/10 hover:bg-indigo-600/20 border border-indigo-600/20 transition-all"
                  >
                    <div className="w-10 h-10 rounded-xl bg-indigo-600 flex items-center justify-center">
                      <Globe className="w-5 h-5" />
                    </div>
                    <div className="text-left">
                      <div className="font-bold">Entire Screen</div>
                      <div className="text-xs text-gray-400 italic">Snip from any application</div>
                    </div>
                  </button>

                  <button 
                    onClick={async () => {
                      setShowCaptureOptions(false);
                      if (isNativeMobile) {
                        try {
                          const result = await ScreenCapture.captureInsideApp() as any;
                          if (result && result.value) {
                            loadIntoExtractPage(result.value);
                          }
                        } catch (err: any) {
                          console.error("Capture Error:", err);
                        }
                      } else {
                        // For Within App on web, we can also use getDisplayMedia or just tell them to import
                        alert("For web, please use 'Entire Screen' and select the current tab, or use 'Import'.");
                      }
                    }}
                    className="flex items-center gap-4 p-4 rounded-2xl bg-white/5 hover:bg-white/10 border border-white/10 transition-all font-sans"
                  >
                    <div className="w-10 h-10 rounded-xl bg-white/10 flex items-center justify-center">
                      <Smartphone className="w-5 h-5" />
                    </div>
                    <div className="text-left">
                      <div className="font-bold">Within App</div>
                      <div className="text-xs text-gray-400 italic">Capture this screen only</div>
                    </div>
                  </button>

                  <button 
                    onClick={() => setShowCaptureOptions(false)}
                    className="w-full py-4 mt-2 text-gray-400 font-medium hover:text-white transition-colors"
                  >
                    Cancel
                  </button>
                </div>
              </motion.div>
            </div>
          )}


          {appState === 'crop' && imgSrc && (
            <motion.div 
              key="crop"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex-1 flex flex-col overflow-hidden h-full max-h-[calc(100vh-64px)]"
            >
              {/* Image Preview Area - Limited Height */}
              <div className="flex-1 overflow-y-auto p-4 flex flex-col items-center justify-center bg-black/60">
                <div className="relative rounded-2xl overflow-hidden border border-white/10 shadow-2xl bg-[#121212] max-w-full">
                  <ReactCrop 
                    crop={crop} 
                    onChange={c => setCrop(c)} 
                    onComplete={c => setCompletedCrop(c)}
                    className="max-w-full"
                  >
                    <img 
                      ref={imgRef} 
                      src={imgSrc} 
                      alt="Crop target" 
                      className="max-h-[50vh] w-auto max-w-full object-contain" 
                      onLoad={onImageLoad} 
                    />
                  </ReactCrop>
                </div>
                <p className="mt-4 text-xs text-gray-500 font-medium">Drag to refine area if needed</p>
              </div>
              
              {/* Bottom Action Bar - Floating and Fixed */}
              <div className="p-6 bg-gradient-to-t from-black to-black/80 border-t border-white/5 pb-10">
                <div className="flex gap-4 max-w-sm mx-auto w-full">
                  <button 
                    onClick={resetApp}
                    className="flex-1 py-4 rounded-2xl bg-white/5 hover:bg-white/10 text-gray-300 font-bold active:scale-[0.98] transition-all flex items-center justify-center gap-2 border border-white/10"
                  >
                    <ChevronLeft className="w-5 h-5" /> Back
                  </button>
                  <button 
                    onClick={handleExtractText}
                    className="flex-[2] py-4 rounded-2xl bg-indigo-600 hover:bg-indigo-500 text-white font-black text-lg active:scale-[0.95] transition-all shadow-xl shadow-indigo-600/30 px-4 flex items-center justify-center gap-3"
                  >
                    <Scissors className="w-5 h-5" /> Extract Text
                  </button>
                </div>
              </div>
            </motion.div>
          )}

          {appState === 'loading' && (
            <motion.div 
              key="loading"
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              className="flex-1 flex flex-col items-center justify-center p-6 text-center"
            >
              {imgSrc && (
                <div className="mb-8 w-full max-w-sm rounded-xl overflow-hidden border border-white/20 bg-black/40 p-1 shadow-2xl relative flex items-center justify-center">
                    <img src={imgSrc} alt="Analyzing" className="w-full max-h-48 object-contain rounded-lg" />
                </div>
              )}
              <Loader2 className="w-12 h-12 text-indigo-500 animate-spin mb-6" />
              <h3 className="text-xl font-bold">Analyzing...</h3>
              <p className="text-gray-400 max-w-xs mt-2 leading-relaxed">
                Reading text and formatting it.
              </p>
              
              <div className="mt-12 p-6 rounded-3xl bg-white/5 border border-white/5 max-w-sm mx-auto">
                <p className="text-xs text-gray-500 mb-4 leading-relaxed">
                  Stuck? If the app didn't open correctly after capture, ensure "Display pop-up windows" permission is ON in Android settings.
                </p>
                <button 
                  onClick={() => checkPending()}
                  className="flex items-center justify-center gap-2 w-full py-3 rounded-xl bg-white/5 hover:bg-white/10 text-sm font-medium transition-all"
                >
                  <RefreshCw className="w-4 h-4" /> Check for Snip
                </button>
              </div>
            </motion.div>
          )}

          {appState === 'result' && (
            <motion.div 
              key="result"
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 0 }}
              className="flex-1 flex flex-col p-4 max-w-2xl mx-auto w-full"
            >
              <div className="flex items-center justify-between mb-4">
                <h2 className="text-xl font-bold text-gray-300">Result</h2>
                <button 
                  onClick={() => copyToClipboard()}
                  className="flex items-center gap-2 px-4 py-2 rounded-xl bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/20 transition-all font-bold"
                >
                  {copied ? <Check className="w-4 h-4" /> : <Copy className="w-4 h-4" />}
                  {copied ? 'Copied' : 'Copy'}
                </button>
              </div>

              {imgSrc ? (
                <div className="mb-4 rounded-xl overflow-hidden border border-white/20 bg-black/40 p-1 shadow-2xl relative min-h-[100px] flex items-center justify-center">
                    <img 
                    src={imgSrc} 
                    alt="Snippet" 
                    className="w-full max-h-64 object-contain rounded-lg"
                    onError={(e) => {
                        console.error("Image failed to load:", imgSrc.substring(0, 50) + "...");
                        alert("Capture Error: Browser could not render the image data. Check if snippet was successful.");
                    }}
                    />
                    <div className="absolute top-2 right-2 px-2 py-1 bg-black/60 rounded text-[10px] text-white">
                        {Math.round(imgSrc.length / 1024)} KB
                    </div>
                </div>
              ) : (
                <div className="mb-4 p-4 border border-dashed border-white/10 rounded-xl text-center text-gray-500 text-xs">
                    (Image memory could not be recovered)
                </div>
              )}

              <div className="flex-1 bg-[#121212] rounded-3xl border border-white/10 p-6 overflow-y-auto shadow-inner mb-6 ring-1 ring-white/5">
                <pre className="whitespace-pre-wrap leading-relaxed text-gray-200 font-sans text-lg">
                  {extractedText || "No text was found."}
                </pre>
              </div>

              <button 
                onClick={resetApp}
                className="w-full py-5 rounded-2xl bg-indigo-600 font-bold hover:bg-indigo-500 transition-all shadow-xl shadow-indigo-900/20 mb-10 active:scale-[0.98]"
              >
                Snip Another
              </button>
            </motion.div>
          )}
        </AnimatePresence>
      </main>
    </div>
  );
}

// Utility to crop image
async function getCroppedImg(image: HTMLImageElement, pixelCrop: PixelCrop): Promise<string> {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');

  if (!ctx) {
    throw new Error('No 2d context');
  }

  const scaleX = image.naturalWidth / image.width;
  const scaleY = image.naturalHeight / image.height;

  canvas.width = pixelCrop.width;
  canvas.height = pixelCrop.height;

  ctx.drawImage(
    image,
    pixelCrop.x * scaleX,
    pixelCrop.y * scaleY,
    pixelCrop.width * scaleX,
    pixelCrop.height * scaleY,
    0,
    0,
    pixelCrop.width,
    pixelCrop.height
  );

  return canvas.toDataURL('image/jpeg', 0.9).split(',')[1];
}
