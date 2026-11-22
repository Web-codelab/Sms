import { useState } from 'react';
import { Check, Copy, Download, FileCode2, FolderOpen } from 'lucide-react';
import type { CodeFileSpec, EndpointConfig } from '../types/sms';

interface CodeViewerPanelProps {
  title: string;
  subtitle: string;
  steps: string[];
  files: CodeFileSpec[];
  endpointConfig: EndpointConfig;
}

export function CodeViewerPanel({
  title,
  subtitle,
  steps,
  files,
  endpointConfig,
}: CodeViewerPanelProps) {
  const [activeFileId, setActiveFileId] = useState<string>(files[0]?.id ?? '');
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const activeFile = files.find((f) => f.id === activeFileId) ?? files[0];

  const handleCopy = async (file: CodeFileSpec) => {
    try {
      await navigator.clipboard.writeText(file.content);
      setCopiedId(file.id);
      setTimeout(() => setCopiedId((prev) => (prev === file.id ? null : prev)), 1800);
    } catch {
      // Clipboard fallback ignored if blocked in non-HTTPS context
    }
  };

  const handleDownload = (file: CodeFileSpec) => {
    const blob = new Blob([file.content], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = file.filename.split('/').pop() || file.filename;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  if (!activeFile) return null;

  return (
    <div className="space-y-6">
      <div className="bg-white border border-slate-200 rounded-xl p-6">
        <div className="flex flex-col lg:flex-row lg:items-start lg:justify-between gap-6">
          <div className="space-y-1.5 max-w-2xl">
            <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
              {title}
            </h2>
            <p className="text-sm text-slate-600 leading-relaxed">
              {subtitle}
            </p>
            <div className="pt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
              <span>Target Base URL:</span>
              <span className="font-mono text-slate-800">{endpointConfig.baseUrl}</span>
              <span aria-hidden="true">·</span>
              <span>{files.length} production files</span>
            </div>
          </div>

          <ol className="grid grid-cols-1 sm:grid-cols-3 gap-3 lg:max-w-xl w-full">
            {steps.map((step, index) => (
              <li
                key={step}
                className="bg-slate-50 border border-slate-200 rounded-lg p-3 text-xs text-slate-700 leading-relaxed"
              >
                <span className="block font-semibold text-slate-900 mb-1 tabular-nums">
                  0{index + 1}. Step
                </span>
                {step}
              </li>
            ))}
          </ol>
        </div>
      </div>

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <div className="border-b border-slate-200 bg-slate-50 px-4 py-3 flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-1.5 overflow-x-auto">
            {files.map((file) => {
              const isActive = file.id === activeFile.id;
              return (
                <button
                  key={file.id}
                  type="button"
                  onClick={() => setActiveFileId(file.id)}
                  className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                    isActive
                      ? 'bg-slate-900 text-white'
                      : 'bg-white text-slate-700 border border-slate-200 hover:bg-slate-100'
                  }`}
                >
                  <FileCode2 className="w-3.5 h-3.5" aria-hidden="true" />
                  <span className="font-mono">{file.filename}</span>
                </button>
              );
            })}
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={() => handleCopy(activeFile)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-white border border-slate-200 text-slate-700 hover:bg-slate-100 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              {copiedId === activeFile.id ? (
                <>
                  <Check className="w-3.5 h-3.5 text-emerald-600" aria-hidden="true" />
                  <span>Copied</span>
                </>
              ) : (
                <>
                  <Copy className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Copy Code</span>
                </>
              )}
            </button>
            <button
              type="button"
              onClick={() => handleDownload(activeFile)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              <Download className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Download File</span>
            </button>
          </div>
        </div>

        <div className="px-4 py-2.5 border-b border-slate-200 bg-white flex items-center justify-between gap-4 text-xs text-slate-600">
          <div className="flex items-center gap-2 min-w-0">
            <FolderOpen className="w-3.5 h-3.5 text-slate-400 shrink-0" aria-hidden="true" />
            <span className="truncate">{activeFile.summary}</span>
          </div>
          <span className="font-mono text-slate-500 uppercase shrink-0">{activeFile.language}</span>
        </div>

        <div className="bg-slate-950 text-slate-100 p-5 overflow-x-auto max-h-[600px] overflow-y-auto">
          <pre className="font-mono text-xs leading-6 whitespace-pre">
            <code>{activeFile.content}</code>
          </pre>
        </div>
      </div>
    </div>
  );
}
