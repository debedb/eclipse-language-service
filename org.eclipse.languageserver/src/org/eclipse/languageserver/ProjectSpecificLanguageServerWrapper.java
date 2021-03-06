/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.languageserver;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.xtext.xbase.lib.Procedures.Procedure2;

import com.google.common.base.Objects;

import io.typefox.lsapi.Diagnostic;
import io.typefox.lsapi.InitializeResult;
import io.typefox.lsapi.Message;
import io.typefox.lsapi.PublishDiagnosticsParams;
import io.typefox.lsapi.impl.ClientCapabilitiesImpl;
import io.typefox.lsapi.impl.DidChangeTextDocumentParamsImpl;
import io.typefox.lsapi.impl.DidOpenTextDocumentParamsImpl;
import io.typefox.lsapi.impl.InitializeParamsImpl;
import io.typefox.lsapi.impl.TextDocumentContentChangeEventImpl;
import io.typefox.lsapi.impl.TextDocumentItemImpl;
import io.typefox.lsapi.impl.VersionedTextDocumentIdentifierImpl;
import io.typefox.lsapi.services.json.JsonBasedLanguageServer;

/**
 * Wraps instantiation, initialization of project-specific instance of the
 * language server
 */
public class ProjectSpecificLanguageServerWrapper {

	private final class DocumentChangeListenenr implements IDocumentListener {
		private URI fileURI;
		private int version = 2;
		private DidChangeTextDocumentParamsImpl change;

		public DocumentChangeListenenr(URI fileURI) {
			this.fileURI = fileURI;
		}

		@Override
		public void documentChanged(DocumentEvent event) {
			this.change.getContentChanges().get(0).setText(event.getDocument().get());
			server.getTextDocumentService().didChange(this.change);
			version++;
		}

		@Override
		public void documentAboutToBeChanged(DocumentEvent event) {
//			try {
				this.change = new DidChangeTextDocumentParamsImpl();
				VersionedTextDocumentIdentifierImpl doc = new VersionedTextDocumentIdentifierImpl();
				doc.setUri(fileURI.toString());
				doc.setVersion(version);
				this.change.setTextDocument(doc);
				TextDocumentContentChangeEventImpl changeEvent = new TextDocumentContentChangeEventImpl();
//				RangeImpl range = new RangeImpl();
//				PositionImpl start = LanguageServerEclipseUtils.toPosition(event.getOffset(), event.getDocument());
//				range.setStart(start);
//				PositionImpl end = LanguageServerEclipseUtils.toPosition(event.getOffset() + event.getLength(), event.getDocument());
//				range.setEnd(end);
//				changeEvent.setRange(range);
//				changeEvent.setRangeLength(event.getLength());
				changeEvent.setText(event.getDocument().get()); // TODO set to value after change
				this.change.setContentChanges(Arrays.asList(new TextDocumentContentChangeEventImpl[] { changeEvent }));
//			} catch (BadLocationException ex) {
//				ex.printStackTrace(); // TODO
//			}
		}
	}

	protected static final String LS_DIAGNOSTIC_MARKER_TYPE = "org.eclipse.languageserver.diagnostic"; //$NON-NLS-1$

	final private StreamConnectionProvider lspStreamProvider;
	private JsonBasedLanguageServer server;
	private IProject project;
	private IContentType contentType;
	private Map<IPath, DocumentChangeListenenr> connectedFiles;
	
	public ProjectSpecificLanguageServerWrapper(IProject project, IContentType contentType, StreamConnectionProvider connection) {
		this.project = project;
		this.contentType = contentType;
		this.lspStreamProvider = connection;
	}

	private void start() throws IOException {
		if (this.server != null) {
			return;
		}
		this.server = new JsonBasedLanguageServer();
		this.server.onError(new Procedure2<String, Throwable>() {
			@Override
			public void apply(String p1, Throwable p2) {
				System.err.println(p1);
				p2.printStackTrace();
			}
		});
		this.lspStreamProvider.start();
		this.server.connect(this.lspStreamProvider.getInputStream(), this.lspStreamProvider.getOutputStream());
		this.server.getProtocol().addErrorListener(new Procedure2<String, Throwable>() {
			@Override
			public void apply(String p1, Throwable p2) {
				System.err.println("error: " + p1);
			}
		});
		this.server.getProtocol().addIncomingMessageListener(new Procedure2<Message, String>() {
			@Override
			public void apply(Message p1, String p2) {
				System.err.println("IN: " + p1.getJsonrpc() + "\n" + p2);
			}
		});
		this.server.getProtocol().addOutgoingMessageListener(new Procedure2<Message, String>() {
			@Override
			public void apply(Message p1, String p2) {
				System.err.println("OUT: " + p1.getJsonrpc() + "\n" + p2);
			}
		});
		// initialize
		InitializeParamsImpl initParams = new InitializeParamsImpl();
		initParams.setRootPath(project.getLocation().toFile().getAbsolutePath());
		String name = "Eclipse IDE";
		if (Platform.getProduct() != null) {
			name = Platform.getProduct().getName();
		}
		initParams.setClientName(name);
		Integer.valueOf(java.lang.management.ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
		initParams.setCapabilities(new ClientCapabilitiesImpl());
		connectDiagnostics();
		CompletableFuture<InitializeResult> result = server.initialize(initParams);
		try {
			InitializeResult initializeResult = result.get();
		} catch (InterruptedException | ExecutionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		this.connectedFiles = new HashMap<>();
	}
	
	private void connectDiagnostics() {
		this.server.getTextDocumentService().onPublishDiagnostics(new Consumer<PublishDiagnosticsParams>() {
			@Override
			public void accept(PublishDiagnosticsParams diagnostics) {
				try {
					// fix issue with file:/// vs file:/
					String uri = diagnostics.getUri();
					IResource resource = LanguageServerEclipseUtils.findResourceFor(uri);
					if (resource == null || !resource.exists()) {
						resource = project;
					}
					Set<IMarker> remainingMarkers = new HashSet<>(Arrays.asList(resource.findMarkers(LS_DIAGNOSTIC_MARKER_TYPE, false, IResource.DEPTH_ONE)));
					for (Diagnostic diagnostic : diagnostics.getDiagnostics()) {
						IMarker associatedMarker = getExistingMarkerFor(resource, diagnostic, remainingMarkers);
						if (associatedMarker == null) {
							createMarkerForDiagnostic(resource, diagnostic);
						} else {
							remainingMarkers.remove(associatedMarker);
						}
					}
					for (IMarker marker : remainingMarkers) {
						marker.delete();
					}
				} catch (CoreException ex) {
					ex.printStackTrace(); // TODO
				}
			}

			private void createMarkerForDiagnostic(IResource resource, Diagnostic diagnostic) {
				try {
					IMarker marker = resource.createMarker(LS_DIAGNOSTIC_MARKER_TYPE);
					marker.setAttribute(IMarker.MESSAGE, diagnostic.getMessage());
					marker.setAttribute(IMarker.SEVERITY, LanguageServerEclipseUtils.toEclipseMarkerSeverity(diagnostic.getSeverity())); // TODO mapping Eclipse <-> LS severity
					if (resource.getType() == IResource.FILE) {
						IFile file = (IFile)resource;
						IDocument document = FileBuffers.getTextFileBufferManager().getTextFileBuffer(file.getFullPath(), LocationKind.IFILE).getDocument();
						marker.setAttribute(IMarker.CHAR_START, LanguageServerEclipseUtils.toOffset(diagnostic.getRange().getStart(), document));
						marker.setAttribute(IMarker.CHAR_END, LanguageServerEclipseUtils.toOffset(diagnostic.getRange().getEnd(), document));
						marker.setAttribute(IMarker.LINE_NUMBER, diagnostic.getRange().getStart().getLine());
					}
				} catch (Exception ex) {
					ex.printStackTrace(); // TODO
				}
			}

			private IMarker getExistingMarkerFor(IResource resource, Diagnostic diagnostic, Set<IMarker> remainingMarkers) {
				ITextFileBuffer textFileBuffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(resource.getFullPath(), LocationKind.IFILE);
				if (textFileBuffer == null) {
					return null;
				}
				IDocument document = textFileBuffer.getDocument();
				for (IMarker marker : remainingMarkers) {
					int startOffset = marker.getAttribute(IMarker.CHAR_START, -1);
					int endOffset = marker.getAttribute(IMarker.CHAR_END, -1);
					try {
						if (marker.getResource().getProjectRelativePath().toString().equals(diagnostic.getSource()) 
								&& LanguageServerEclipseUtils.toOffset(diagnostic.getRange().getStart(), document) == startOffset + 1
								&& LanguageServerEclipseUtils.toOffset(diagnostic.getRange().getEnd(), document) == endOffset + 1
								&& Objects.equal(marker.getAttribute(IMarker.MESSAGE), diagnostic.getMessage())) {
							return marker;
						}
					} catch (Exception e) {
						e.printStackTrace(); // TODO
					}
				}
				return null;
			}
		});
	}

	private void stop() {
		this.lspStreamProvider.stop();
		this.server.shutdown();
		this.server = null;
	}

	public void connect(IFile file, final IDocument document) throws IOException {
		start();
		if (this.connectedFiles.containsKey(file.getLocation())) {
			return;
		}
		// add a document buffer
		DidOpenTextDocumentParamsImpl open = new DidOpenTextDocumentParamsImpl();
		TextDocumentItemImpl textDocument = new TextDocumentItemImpl();
		textDocument.setUri(file.getLocationURI().toString());
		textDocument.setText(document.get());
		textDocument.setLanguageId(file.getFileExtension());
		open.setTextDocument(textDocument);
		this.server.getTextDocumentService().didOpen(open);
		
		DocumentChangeListenenr listener = new DocumentChangeListenenr(file.getLocationURI());
		document.addDocumentListener(listener);
		this.connectedFiles.put(file.getLocation(), listener);
	}
	
	public void disconnect(IFile file, IDocument document) {
		document.removeDocumentListener(this.connectedFiles.get(file.getLocation()));
		this.connectedFiles.remove(file.getLocation());
		if (this.connectedFiles.isEmpty()) {
			stop();
		}
	}

	public JsonBasedLanguageServer getServer() {
		return server;
	}
}
