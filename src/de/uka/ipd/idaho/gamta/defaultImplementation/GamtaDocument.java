/*
 * Copyright (c) 2006-, IPD Boehm, Universitaet Karlsruhe (TH) / KIT, by Guido Sautter
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Universitaet Karlsruhe (TH) nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY UNIVERSITAET KARLSRUHE (TH) / KIT AND CONTRIBUTORS 
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE REGENTS OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.uka.ipd.idaho.gamta.defaultImplementation;


import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TreeSet;

import de.uka.ipd.idaho.gamta.Annotation;
import de.uka.ipd.idaho.gamta.AnnotationListener;
import de.uka.ipd.idaho.gamta.AnnotationUtils;
import de.uka.ipd.idaho.gamta.Attributed;
import de.uka.ipd.idaho.gamta.CharSequenceListener;
import de.uka.ipd.idaho.gamta.DocumentRoot;
import de.uka.ipd.idaho.gamta.Gamta;
import de.uka.ipd.idaho.gamta.MutableAnnotation;
import de.uka.ipd.idaho.gamta.MutableCharSequence;
import de.uka.ipd.idaho.gamta.MutableTokenSequence;
import de.uka.ipd.idaho.gamta.QueriableAnnotation;
import de.uka.ipd.idaho.gamta.Token;
import de.uka.ipd.idaho.gamta.TokenSequence;
import de.uka.ipd.idaho.gamta.TokenSequenceListener;
import de.uka.ipd.idaho.gamta.Tokenizer;
import de.uka.ipd.idaho.gamta.util.ImmutableAnnotation;
//import java.util.Vector;

/**
 * Markup overlay for MutableTokenSequence instances
 * 
 * @author sautter
 */
public class GamtaDocument extends AbstractAttributed implements DocumentRoot {
	
	private String annotationId = Gamta.getAnnotationID();
	
	private MutableTokenSequence tokenData; // the token sequence the annotations kept in this document refers to
	
	private AnnotationStore annotations = new AnnotationStore(); // the storage for the annotations
	private AnnotationAdjuster adjuster = new AnnotationAdjuster(); // a listener to the underlaying token sequence, responsible for adjusting the annotations in the face of changes
	private AnnotationBase modificationSource = null; // the base of the view through which the token sequence is being modified (always null, except if the token sequence is being modified through a MutableAnnotationView belonging to this document)
	
	private Properties documentProperties = new Properties(); // the store for document properties
	
	private String annotationNestingOrder = Gamta.getAnnotationNestingOrder(); // the nesting order for annotations to this document 
	private Comparator nestingOrder = AnnotationUtils.getComparator(this.annotationNestingOrder);
	private Comparator typeNestingOrder = AnnotationUtils.getTypeComparator(this.annotationNestingOrder);
	private int orderModCount = 0;
	
	private ArrayList annotationListeners = null;
	
	/**
	 * Constructor creating an annotation overlay for a mutable token sequence
	 * @param tokens the Tokens of this document's text
	 */
	public GamtaDocument(MutableTokenSequence tokens) {
		
		//	constuction as copy of other document or part of it
		if (tokens instanceof MutableAnnotation)
			this.initCopy((MutableAnnotation) tokens);
		
		//	constuction as annotation overlay to plain mutable token sequence
		else this.tokenData = tokens;
		
		//	listen to changes
		this.tokenData.addTokenSequenceListener(this.adjuster);
	}
	
	/**	Constructor cloning a document. Annotation IDs will be preserved
	 * @param	original	the document to copy
	 */
	public GamtaDocument(MutableAnnotation original) {
		this((QueriableAnnotation) original);
	}
	
	/**	Constructor cloning a document. Annotation IDs will be preserved
	 * @param	original	the document to copy
	 */
	public GamtaDocument(QueriableAnnotation original) {
		
		//	copy content
		this.initCopy(original);
		
		//	listen to changes
		this.tokenData.addTokenSequenceListener(this.adjuster);
	}
	
	private final void initCopy(QueriableAnnotation original) {
		
		//	copy tokens
		this.tokenData = Gamta.copyTokenSequence(original);
		
		//	copy attributes
		this.copyAttributes(original);
		
		//	copy document properties
		String[] documentPropertyNames = original.getDocumentPropertyNames();
		for (int a = 0; a < documentPropertyNames.length; a++)
			this.setDocumentProperty(documentPropertyNames[a], original.getDocumentProperty(documentPropertyNames[a]));
		
		//	copy Annotations (including annotation ID)
		Annotation[] annotations = original.getAnnotations();
		for (int a = 0; a < annotations.length; a++) {
			if (DocumentRoot.DOCUMENT_TYPE.equals(annotations[a].getType()))
				continue;
			Annotation annot = this.addAnnotation(annotations[a]);
			if (annot == null) {
				System.out.println("GamtaDocument: could not copy annotation " + annotations[a].getType() + " at " + annotations[a].getStartIndex() + "-" + annotations[a].getEndIndex());
				System.out.println("  " + annotations[a].toXML());
			}
			else annot.setAttribute(ANNOTATION_ID_ATTRIBUTE, annotations[a].getAnnotationID());
		}
		
		//	copy annotation nesting order
		this.setAnnotationNestingOrder(original.getAnnotationNestingOrder());
	}
	
	public void printSanityCheck(int from, int to) {
		if (this.tokenData instanceof TokenizedMutableCharSequence)
			((TokenizedMutableCharSequence) this.tokenData).printSanityCheck(from, to);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String)
	 */
	public Object getAttribute(String name) {
		if (START_INDEX_ATTRIBUTE.equals(name))
			return new Integer(this.getStartIndex());
		else if (SIZE_ATTRIBUTE.equals(name))
			return new Integer(this.size());
		else if (END_INDEX_ATTRIBUTE.equals(name))
			return new Integer(this.getEndIndex());
		else if (ANNOTATION_VALUE_ATTRIBUTE.equals(name))
			return this.getValue();
		else if (ANNOTATION_ID_ATTRIBUTE.equals(name))
			return this.annotationId;
		else return super.getAttribute(name);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String, java.lang.Object)
	 */
	public Object getAttribute(String name, Object def) {
		if (START_INDEX_ATTRIBUTE.equals(name))
			return new Integer(this.getStartIndex());
		else if (SIZE_ATTRIBUTE.equals(name))
			return new Integer(this.size());
		else if (END_INDEX_ATTRIBUTE.equals(name))
			return new Integer(this.getEndIndex());
		else if (ANNOTATION_VALUE_ATTRIBUTE.equals(name))
			return this.getValue();
		else if (ANNOTATION_ID_ATTRIBUTE.equals(name))
			return this.annotationId;
		else return super.getAttribute(name, def);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.AbstractAttributed#hasAttribute(java.lang.String)
	 */
	public boolean hasAttribute(String name) {
		return (false
				|| START_INDEX_ATTRIBUTE.equals(name)
				|| SIZE_ATTRIBUTE.equals(name)
				|| END_INDEX_ATTRIBUTE.equals(name)
				|| ANNOTATION_VALUE_ATTRIBUTE.equals(name)
				|| ANNOTATION_ID_ATTRIBUTE.equals(name)
				|| super.hasAttribute(name)
			);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.AbstractAttributed#setAttribute(java.lang.String, java.lang.Object)
	 */
	public Object setAttribute(String name, Object value) {
		if (ANNOTATION_ID_ATTRIBUTE.equals(name)) {
			if ((value != null) && (value instanceof String) && (value.toString().trim().length() == this.annotationId.length())) {
				String oldId = this.annotationId;
				this.annotationId = value.toString();
				this.notifyAnnotationAttributeChanged(null, name, oldId);
				return oldId;
			}
			else return value;
		}
		else {
			Object oldValue = super.setAttribute(name, value);
			if ((value == null) ? (oldValue != null) : !value.equals(oldValue))
				this.notifyAnnotationAttributeChanged(null, name, oldValue);
			return oldValue;
		}
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.Annotation#changeTypeTo(java.lang.String)
	 */
	public String changeTypeTo(String newType) {
		//	the type of a document root never changes
		return newType;
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getAnnotationID()
	 */
	public String getAnnotationID() {
		return this.annotationId;
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getStartIndex()
	 */
	public int getStartIndex() {
		return 0;
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getEndIndex()
	 */
	public int getEndIndex() {
		return this.size();
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getType()
	 */
	public String getType() {
		return DocumentRoot.DOCUMENT_TYPE;
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getValue()
	 */
	public String getValue() {
		return ((this.tokenData.size() == 0) ? "" : this.tokenData.subSequence(this.firstToken().getStartOffset(), this.lastToken().getEndOffset()).toString());
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#toXML()
	 */
	public String toXML() {
		return (AnnotationUtils.produceStartTag(this) + AnnotationUtils.escapeForXml(this.getValue()) + AnnotationUtils.produceEndTag(this));
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.Annotation#getDocument()
	 */
	public QueriableAnnotation getDocument() {
		return this;
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getDocumentProperty(java.lang.String)
	 */
	public String getDocumentProperty(String propertyName) {
		return this.getDocumentProperty(propertyName, null);
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getDocumentProperty(java.lang.String, java.lang.String)
	 */
	public String getDocumentProperty(String propertyName, String defaultValue) {
		return this.documentProperties.getProperty(propertyName, defaultValue);
	}

	/* (non-Javadoc)
	 * @see de.gamta.Annotation#getDocumentPropertyNames()
	 */
	public String[] getDocumentPropertyNames() {
		ArrayList names = new ArrayList(this.documentProperties.keySet());
		Collections.sort(names);
		return ((String[]) names.toArray(new String[names.size()]));
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.DocumentRoot#setDocumentProperty(java.lang.String, java.lang.String)
	 */
	public String setDocumentProperty(String propertyName, String value) {
		return ((String) this.documentProperties.setProperty(propertyName, value));
	}

	/* (non-Javadoc)
	 * @see de.gamta.DocumentRoot#removeDocumentProperty(java.lang.String)
	 */
	public String removeDocumentProperty(String propertyName) {
		return ((String) this.documentProperties.remove(propertyName));
	}

	/* (non-Javadoc)
	 * @see de.gamta.DocumentRoot#clearDocumentProperties()
	 */
	public void clearDocumentProperties() {
		this.documentProperties.clear();
	}

	/* (non-Javadoc)
	 * @see de.gamta.DocumentRoot#getAnnotationNestingOrder()
	 */
	public String getAnnotationNestingOrder() {
		return this.annotationNestingOrder;
	}

	/* (non-Javadoc)
	 * @see de.gamta.DocumentRoot#setAnnotationNestingOrder(java.lang.String)
	 */
	public String setAnnotationNestingOrder(String ano) {
		String old = this.annotationNestingOrder;
		this.annotationNestingOrder = ((ano == null) ? DEFAULT_ANNOTATION_NESTING_ORDER : ano);
		this.typeNestingOrder = AnnotationUtils.getTypeComparator(this.annotationNestingOrder);
		this.nestingOrder = AnnotationUtils.getComparator(this.annotationNestingOrder);
		this.orderModCount++;
		return old;
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableTokenSequence#addTokenSequenceListener(de.gamta.TokenSequenceListener)
	 */
	public void addTokenSequenceListener(TokenSequenceListener tsl) {
		this.tokenData.addTokenSequenceListener(tsl);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.MutableTokenSequence#removeTokenSequenceListener(de.gamta.TokenSequenceListener)
	 */
	public void removeTokenSequenceListener(TokenSequenceListener tsl) {
		this.tokenData.removeTokenSequenceListener(tsl);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.CharSpan#getEndOffset()
	 */
	public int getEndOffset() {
		return this.length();
	}

	/* (non-Javadoc)
	 * @see de.gamta.CharSpan#getStartOffset()
	 */
	public int getStartOffset() {
		return 0;
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#addChar(char)
	 */
	public void addChar(char ch) {
		this.tokenData.addChar(ch);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#addChars(java.lang.CharSequence)
	 */
	public void addChars(CharSequence chars) {
		this.tokenData.addChars(chars);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#addCharSequenceListener(de.gamta.CharSequenceListener)
	 */
	public void addCharSequenceListener(CharSequenceListener csl) {
		this.tokenData.addCharSequenceListener(csl);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#addTokens(java.lang.CharSequence)
	 */
	public CharSequence addTokens(CharSequence tokens) {
		return this.tokenData.addTokens(tokens);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#charAt(int)
	 */
	public char charAt(int index) {
		return this.tokenData.charAt(index);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#clear()
	 */
	public void clear() {
		this.tokenData.clear();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#compareTo(java.lang.Object)
	 */
	public int compareTo(Object obj) {
		return -1;
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#finalize()
	 */
	protected void finalize() throws Throwable {
		super.finalize();
		this.tokenData.removeTokenSequenceListener(this.adjuster);
		this.annotations.clear();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#firstToken()
	 */
	public Token firstToken() {
		return this.tokenData.firstToken();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#firstValue()
	 */
	public String firstValue() {
		return this.tokenData.firstValue();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#getLeadingWhitespace()
	 */
	public String getLeadingWhitespace() {
		return this.tokenData.getLeadingWhitespace();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#getMutableSubsequence(int, int)
	 */
	public MutableTokenSequence getMutableSubsequence(int start, int size) {
		return this.tokenData.getMutableSubsequence(start, size);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#getSubsequence(int, int)
	 */
	public TokenSequence getSubsequence(int start, int size) {
		return this.tokenData.getSubsequence(start, size);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#getTokenizer()
	 */
	public Tokenizer getTokenizer() {
		return this.tokenData.getTokenizer();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#getWhitespaceAfter(int)
	 */
	public String getWhitespaceAfter(int index) {
		return this.tokenData.getWhitespaceAfter(index);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#insertChar(char, int)
	 */
	public void insertChar(char ch, int offset) {
		this.tokenData.insertChar(ch, offset);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#insertChars(java.lang.CharSequence, int)
	 */
	public void insertChars(CharSequence chars, int offset) {
		this.tokenData.insertChars(chars, offset);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#insertTokensAt(java.lang.CharSequence, int)
	 */
	public CharSequence insertTokensAt(CharSequence tokens, int index) {
		return this.tokenData.insertTokensAt(tokens, index);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#lastToken()
	 */
	public Token lastToken() {
		return this.tokenData.lastToken();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#lastValue()
	 */
	public String lastValue() {
		return this.tokenData.lastValue();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#length()
	 */
	public int length() {
		return this.tokenData.length();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#removeChar(int)
	 */
	public char removeChar(int offset) {
		return this.tokenData.removeChar(offset);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#removeChars(int, int)
	 */
	public CharSequence removeChars(int offset, int length) {
		return this.tokenData.removeChars(offset, length);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#removeCharSequenceListener(de.gamta.CharSequenceListener)
	 */
	public void removeCharSequenceListener(CharSequenceListener csl) {
		this.tokenData.removeCharSequenceListener(csl);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#removeTokensAt(int, int)
	 */
	public TokenSequence removeTokensAt(int index, int size) {
		return this.tokenData.removeTokensAt(index, size);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#setChar(char, int)
	 */
	public char setChar(char ch, int offset) {
		return this.tokenData.setChar(ch, offset);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#setChars(java.lang.CharSequence, int, int)
	 */
	public CharSequence setChars(CharSequence chars, int offset, int length) {
		return this.tokenData.setChars(chars, offset, length);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#setLeadingWhitespace(java.lang.CharSequence)
	 */
	public CharSequence setLeadingWhitespace(CharSequence whitespace) throws IllegalArgumentException {
		return this.tokenData.setLeadingWhitespace(whitespace);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#setValueAt(java.lang.CharSequence, int)
	 */
	public CharSequence setValueAt(CharSequence value, int index) throws IllegalArgumentException {
		return this.tokenData.setValueAt(value, index);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#setWhitespaceAfter(java.lang.CharSequence, int)
	 */
	public CharSequence setWhitespaceAfter(CharSequence whitespace, int index) throws IllegalArgumentException {
		return this.tokenData.setWhitespaceAfter(whitespace, index);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#size()
	 */
	public int size() {
		return this.tokenData.size();
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#subSequence(int, int)
	 */
	public CharSequence subSequence(int start, int end) {
		return this.tokenData.subSequence(start, end);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#tokenAt(int)
	 */
	public Token tokenAt(int index) {
		return this.tokenData.tokenAt(index);
	}

	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#toString()
	 */
	public String toString() {
		return this.tokenData.toString();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.defaultImplementation.GamtaTokenSequence#valueAt(int)
	 */
	public String valueAt(int index) {
		return this.tokenData.valueAt(index);
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableCharSequence#mutableSubSequence(int, int)
	 */
	public MutableCharSequence mutableSubSequence(int start, int end) {
		return this.tokenData.mutableSubSequence(start, end);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.QueriableAnnotation#getAbsoluteStartIndex()
	 */
	public int getAbsoluteStartIndex() {
		return 0;
	}

	/* (non-Javadoc)
	 * @see de.gamta.QueriableAnnotation#getAbsoluteStartOffset()
	 */
	public int getAbsoluteStartOffset() {
		return 0;
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotation(java.lang.String)
	 */
	public QueriableAnnotation getAnnotation(String id) {
		AnnotationBase ab = this.annotations.getAnnotation(id);
		return ((ab == null) ? null : new QueriableAnnotationView(ab, this));
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.QueriableAnnotation#getAnnotations()
	 */
	public QueriableAnnotation[] getAnnotations() {
		return this.getAnnotations(null);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.QueriableAnnotation#getAnnotations(java.lang.String)
	 */
	public QueriableAnnotation[] getAnnotations(String type) {
		return this.wrapAnnotationBasesQueriable(this.annotations.getAnnotations(type), this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsSpanning(int, int)
	 */
	public QueriableAnnotation[] getAnnotationsSpanning(int startIndex, int endIndex) {
		return this.getAnnotationsSpanning(null, startIndex, endIndex);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsSpanning(java.lang.String, int, int)
	 */
	public QueriableAnnotation[] getAnnotationsSpanning(String type, int startIndex, int endIndex) {
		return this.wrapAnnotationBasesQueriable(this.annotations.getAnnotationsSpanning(type, startIndex, endIndex), this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsOverlapping(int, int)
	 */
	public QueriableAnnotation[] getAnnotationsOverlapping(int startIndex, int endIndex) {
		return this.getAnnotationsOverlapping(null, startIndex, endIndex);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsOverlapping(java.lang.String, int, int)
	 */
	public QueriableAnnotation[] getAnnotationsOverlapping(String type, int startIndex, int endIndex) {
		return this.wrapAnnotationBasesQueriable(this.annotations.getAnnotationsOverlapping(type, startIndex, endIndex), this);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.QueriableAnnotation#getAnnotationTypes()
	 */
	public String[] getAnnotationTypes() {
		return this.annotations.getAnnotationTypes();
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#addAnnotation(de.gamta.Annotation)
	 */
	public MutableAnnotation addAnnotation(Annotation annotation) {
		
		//	check parameter
		if (annotation == null)
			return null;
		
		//	create AnnotationBase
		AnnotationBase ab = this.addAnnotationAbsolute(annotation.getType(), annotation.getStartIndex(), annotation.size());
		
		//	check success
		if (ab == null)
			return null;
		
		//	copy attributes
		ab.copyAttributes(annotation);
		
		//	notify listeners
		this.notifyAnnotationAdded(ab);
		
		//	return Annotation
		return new MutableAnnotationView(ab, this);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#addAnnotation(java.lang.String, int, int)
	 */
	public MutableAnnotation addAnnotation(String type, int startIndex, int size) {
		
		//	create AnnotationBase
		AnnotationBase ab = this.addAnnotationAbsolute(type, startIndex, size);
		
		//	check success
		if (ab == null)
			return null;
		
		//	notify listeners
		this.notifyAnnotationAdded(ab);
		
		//	return Annotation
		return new MutableAnnotationView(ab, this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#addAnnotation(int, int, java.lang.String)
	 */
	public MutableAnnotation addAnnotation(int startIndex, int endIndex, String type) {
		return this.addAnnotation(type, startIndex, (endIndex - startIndex));
	}
	
	//	add an Annotation
	private AnnotationBase addAnnotationAbsolute(String type, int startIndex, int size) {
		
		//	check parameters
		if ((startIndex < 0) || (size < 1))
			return null;
		
		//	create Annotation
		AnnotationBase ab = new AnnotationBase(type, startIndex, size);
		this.annotations.storeAnnotation(ab);
		
		//	return Annotation
		return ab;
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotation(java.lang.String)
	 */
	public MutableAnnotation getMutableAnnotation(String id) {
		AnnotationBase ab = this.annotations.getAnnotation(id);
		return ((ab == null) ? null : new MutableAnnotationView(ab, this));
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#getMutableAnnotations()
	 */
	public MutableAnnotation[] getMutableAnnotations() {
		return this.getMutableAnnotations(null);
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#getMutableAnnotations(java.lang.String)
	 */
	public MutableAnnotation[] getMutableAnnotations(String type) {
		return this.wrapAnnotationBasesMutable(this.annotations.getAnnotations(type), this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsSpanning(int, int)
	 */
	public MutableAnnotation[] getMutableAnnotationsSpanning(int startIndex, int endIndex) {
		return this.getMutableAnnotationsSpanning(null, startIndex, endIndex);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsSpanning(java.lang.String, int, int)
	 */
	public MutableAnnotation[] getMutableAnnotationsSpanning(String type, int startIndex, int endIndex) {
		return this.wrapAnnotationBasesMutable(this.annotations.getAnnotationsSpanning(type, startIndex, endIndex), this);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsOverlapping(int, int)
	 */
	public MutableAnnotation[] getMutableAnnotationsOverlapping(int startIndex, int endIndex) {
		return this.getMutableAnnotationsOverlapping(null, startIndex, endIndex);
	}
	
	/* (non-Javadoc)
	 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsOverlapping(java.lang.String, int, int)
	 */
	public MutableAnnotation[] getMutableAnnotationsOverlapping(String type, int startIndex, int endIndex) {
		return this.wrapAnnotationBasesMutable(this.annotations.getAnnotationsOverlapping(type, startIndex, endIndex), this);
	}
	
	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#removeAnnotation(de.gamta.Annotation)
	 */
	public Annotation removeAnnotation(Annotation annotation) {
//		AnnotationBase ab = this.annotations.removeAnnotation(0, annotation);
		AnnotationBase ab = this.annotations.removeAnnotation(annotation);
		if (ab == null)
			return annotation;
		
		//	notify listeners
		this.notifyAnnotationRemoved(ab);
		
		//	create standalone Annotation
		Annotation ra = new TemporaryAnnotation(this, ab.getType(), ab.absoluteStartIndex, ab.size);
		ra.copyAttributes(ab);
		
		//	return Annotation
		return ra;
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#removeTokens(de.gamta.Annotation)
	 */
	public TokenSequence removeTokens(Annotation annotation) {
		return this.removeTokensAt(annotation.getStartIndex(), annotation.size());
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#addAnnotationListener(de.gamta.AnnotationListener)
	 */
	public void addAnnotationListener(AnnotationListener al) {
		if (al != null) {
			if (this.annotationListeners == null)
				this.annotationListeners = new ArrayList(2);
			this.annotationListeners.add(al);
		}
	}

	/* (non-Javadoc)
	 * @see de.gamta.MutableAnnotation#removeAnnotationListener(de.gamta.AnnotationListener)
	 */
	public void removeAnnotationListener(AnnotationListener al) {
		if (this.annotationListeners != null)
			this.annotationListeners.remove(al);
	}
	
	void notifyAnnotationAdded(AnnotationBase added) {
		if (this.annotationListeners == null)
			return;
		QueriableAnnotation doc = new ImmutableAnnotation(this);
		Annotation addedAnnotation = new ImmutableAnnotation(new QueriableAnnotationView(added, this));
		for (int l = 0; l < this.annotationListeners.size(); l++) try {
			((AnnotationListener) this.annotationListeners.get(l)).annotationAdded(doc, addedAnnotation);
		}
		catch (Exception e) {
			System.out.println("Exception notifying annotation added: " + e.getMessage());
			e.printStackTrace(System.out);
		}
	}
	
	void notifyAnnotationRemoved(AnnotationBase removed) {
		if (this.annotationListeners == null)
			return;
		QueriableAnnotation doc = new ImmutableAnnotation(this);
		Annotation removedAnnotation = new TemporaryAnnotation(doc, removed.getType(), removed.absoluteStartIndex, removed.size);
		removedAnnotation.copyAttributes(removed);
		removedAnnotation.setAttribute(ANNOTATION_ID_ATTRIBUTE, removed.annotationId);
		for (int l = 0; l < this.annotationListeners.size(); l++) try {
			((AnnotationListener) this.annotationListeners.get(l)).annotationRemoved(doc, removedAnnotation);
		}
		catch (Exception e) {
			System.out.println("Exception notifying annotation removed: " + e.getMessage());
			e.printStackTrace(System.out);
		}
	}
	
	void notifyAnnotationTypeChanged(AnnotationBase reTyped, String oldType) {
		if (this.annotationListeners == null)
			return;
		QueriableAnnotation doc = new ImmutableAnnotation(this);
		Annotation reTypedAnnotation = new ImmutableAnnotation(new QueriableAnnotationView(reTyped, this));
		for (int l = 0; l < this.annotationListeners.size(); l++) try {
			((AnnotationListener) this.annotationListeners.get(l)).annotationTypeChanged(doc, reTypedAnnotation, oldType);
		}
		catch (Exception e) {
			System.out.println("Exception notifying annotation type change: " + e.getMessage());
			e.printStackTrace(System.out);
		}
	}
	
	void notifyAnnotationAttributeChanged(AnnotationBase target, String attributeName, Object oldValue) {
		if (this.annotationListeners == null)
			return;
		QueriableAnnotation doc = new ImmutableAnnotation(this);
		Annotation targetAnnotation = ((target == null) ? doc : new ImmutableAnnotation(new QueriableAnnotationView(target, this)));
		for (int l = 0; l < this.annotationListeners.size(); l++) try {
			((AnnotationListener) this.annotationListeners.get(l)).annotationAttributeChanged(doc, targetAnnotation, attributeName, oldValue);
		}
		catch (Exception e) {
			System.out.println("Exception notifying annotation attribute change: " + e.getMessage());
			e.printStackTrace(System.out);
		}
	}
	
	private class AnnotationAdjuster implements TokenSequenceListener {
		/* (non-Javadoc)
		 * @see de.gamta.TokenSequenceListener#tokenSequenceChanged(de.gamta.MutableTokenSequence.TokenSequenceEvent)
		 */
		public synchronized void tokenSequenceChanged(TokenSequenceEvent change) {
			annotations.tokenSequenceChanged(change);
		}
	}
	
	/**	a queriable view of an annotation, behaving relative to the annotation its was retrieved from
	 */
	private class QueriableAnnotationView implements QueriableAnnotation {
		AnnotationBase data; // the AnnotationBase holding the actual data
		QueriableAnnotation base; // the QueriableAnnotation this view was retrieved from
		QueriableAnnotationView(AnnotationBase data, QueriableAnnotation base) {
			this.data = data;
			this.base = base;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#changeTypeTo(java.lang.String)
		 */
		public String changeTypeTo(String newType) {
			//	change type
			String oldType = this.data.changeTypeTo(newType);
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationTypeChanged(this.data, oldType);
					return oldType;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationTypeChanged(this.data, oldType);
			
			//	return old type
			return oldType;
		}
		/* (non-Javadoc)
		 * @see de.gamta.Annotation#getDocumentProperty(java.lang.String)
		 */
		public String getDocumentProperty(String propertyName) {
			return GamtaDocument.this.getDocumentProperty(propertyName);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Annotation#getDocumentProperty(java.lang.String, java.lang.String)
		 */
		public String getDocumentProperty(String propertyName, String defaultValue) {
			return GamtaDocument.this.getDocumentProperty(propertyName, defaultValue);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Annotation#getDocumentPropertyNames()
		 */
		public String[] getDocumentPropertyNames() {
			return GamtaDocument.this.getDocumentPropertyNames();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#charAt(int)
		 */
		public char charAt(int index) {
			return this.data.charAt(index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#compareTo(java.lang.Object)
		 */
		public int compareTo(Object obj) {
			if (obj instanceof Annotation) {
				int c = AnnotationUtils.compare(this, ((Annotation) obj));
				if (c != 0) return c;
				c = typeNestingOrder.compare(this.getType(), ((Annotation) obj).getType());
				if (c != 0) return c;
				return this.getType().compareTo(((Annotation) obj).getType());
			}
			else return -1;
		}
		/* (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		public boolean equals(Object obj) {
			return (this.compareTo(obj) == 0);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#firstToken()
		 */
		public Token firstToken() {
			return new TokenView(this.data.firstToken(), this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#firstValue()
		 */
		public String firstValue() {
			return this.data.firstValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAbsoluteStartIndex()
		 */
		public int getAbsoluteStartIndex() {
			return this.data.absoluteStartIndex;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAbsoluteStartOffset()
		 */
		public int getAbsoluteStartOffset() {
			return this.data.getAbsoluteStartOffset();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAnnotationID()
		 */
		public String getAnnotationID() {
			return this.data.getAnnotationID();
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotation(java.lang.String)
		 */
		public QueriableAnnotation getAnnotation(String id) {
			AnnotationBase ab = this.data.getAnnotation(id);
			return ((ab == null) ? null : new QueriableAnnotationView(ab, this));
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAnnotations()
		 */
		public QueriableAnnotation[] getAnnotations() {
			return this.getAnnotations(null);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAnnotations(java.lang.String)
		 */
		public QueriableAnnotation[] getAnnotations(String type) {
			return wrapAnnotationBasesQueriable(this.data.getAnnotations(type), this);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsSpanning(int, int)
		 */
		public QueriableAnnotation[] getAnnotationsSpanning(int startIndex, int endIndex) {
			return this.getAnnotationsSpanning(null, startIndex, endIndex);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsSpanning(java.lang.String, int, int)
		 */
		public QueriableAnnotation[] getAnnotationsSpanning(String type, int startIndex, int endIndex) {
			return wrapAnnotationBasesQueriable(this.data.getAnnotationsSpanning(type, startIndex, endIndex), this);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsOverlapping(int, int)
		 */
		public QueriableAnnotation[] getAnnotationsOverlapping(int startIndex, int endIndex) {
			return this.getAnnotationsOverlapping(null, startIndex, endIndex);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.QueriableAnnotation#getAnnotationsOverlapping(java.lang.String, int, int)
		 */
		public QueriableAnnotation[] getAnnotationsOverlapping(String type, int startIndex, int endIndex) {
			return wrapAnnotationBasesQueriable(this.data.getAnnotationsOverlapping(type, startIndex, endIndex), this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getAnnotationTypes()
		 */
		public String[] getAnnotationTypes() {
			return this.data.getAnnotationTypes();
		}
		/* (non-Javadoc)
		 * @see de.gamta.QueriableAnnotation#getAnnotationNestingOrder()
		 */
		public String getAnnotationNestingOrder() {
			return annotationNestingOrder;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getEndIndex()
		 */
		public int getEndIndex() {
			return (this.data.getEndIndex() - this.base.getAbsoluteStartIndex());
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getEndOffset()
		 */
		public int getEndOffset() {
			return (this.data.getEndOffset() - this.base.getAbsoluteStartOffset());
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getLeadingWhitespace()
		 */
		public String getLeadingWhitespace() {
			return this.data.getLeadingWhitespace();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getStartIndex()
		 */
		public int getStartIndex() {
			return (this.data.absoluteStartIndex - this.base.getAbsoluteStartIndex());
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getStartOffset()
		 */
		public int getStartOffset() {
			return (this.data.getAbsoluteStartOffset() - this.base.getAbsoluteStartOffset());
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getSubsequence(int, int)
		 */
		public TokenSequence getSubsequence(int start, int size) {
			return this.data.getSubsequence(start, size);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getTokenizer()
		 */
		public Tokenizer getTokenizer() {
			return this.data.getTokenizer();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getType()
		 */
		public String getType() {
			return this.data.getType();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getValue()
		 */
		public String getValue() {
			return this.data.getValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Annotation#toXML()
		 */
		public String toXML() {
			return (AnnotationUtils.produceStartTag(this) + AnnotationUtils.escapeForXml(this.getValue()) + AnnotationUtils.produceEndTag(this));
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.Annotation#getDocument()
		 */
		public QueriableAnnotation getDocument() {
			return GamtaDocument.this;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getWhitespaceAfter(int)
		 */
		public String getWhitespaceAfter(int index) {
			return this.data.getWhitespaceAfter(index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#lastToken()
		 */
		public Token lastToken() {
			return new TokenView(this.data.lastToken(), this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#lastValue()
		 */
		public String lastValue() {
			return this.data.lastValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#length()
		 */
		public int length() {
			return this.data.length();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#size()
		 */
		public int size() {
			return this.data.size;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#subSequence(int, int)
		 */
		public CharSequence subSequence(int start, int end) {
			return this.data.subSequence(start, end);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#tokenAt(int)
		 */
		public Token tokenAt(int index) {
			return new TokenView(this.data.tokenAt(index), this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#toString()
		 */
		public String toString() {
			return this.getValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#valueAt(int)
		 */
		public String valueAt(int index) {
			return this.data.valueAt(index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#clearAttributes()
		 */
		public void clearAttributes() {
			this.data.clearAttributes();
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(this.data, null, null);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(this.data, null, null);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#copyAttributes(de.gamta.Attributed)
		 */
		public void copyAttributes(Attributed source) {
			this.data.copyAttributes(source);
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(this.data, null, null);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(this.data, null, null);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String, java.lang.Object)
		 */
		public Object getAttribute(String name, Object def) {
			if (START_INDEX_ATTRIBUTE.equals(name)) return new Integer(this.getStartIndex());
			else if (END_INDEX_ATTRIBUTE.equals(name)) return new Integer(this.getEndIndex());
			else return this.data.getAttribute(name, def);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String)
		 */
		public Object getAttribute(String name) {
			if (START_INDEX_ATTRIBUTE.equals(name)) return new Integer(this.getStartIndex());
			else if (END_INDEX_ATTRIBUTE.equals(name)) return new Integer(this.getEndIndex());
			else return this.data.getAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttributeNames()
		 */
		public String[] getAttributeNames() {
			return this.data.getAttributeNames();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#hasAttribute(java.lang.String)
		 */
		public boolean hasAttribute(String name) {
			return this.data.hasAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#removeAttribute(java.lang.String)
		 */
		public Object removeAttribute(String name) {
			Object oldValue = this.data.removeAttribute(name);
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(this.data, name, oldValue);
					return oldValue;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(this.data, name, oldValue);
			
			return oldValue;
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.Attributed#setAttribute(java.lang.String)
		 */
		public void setAttribute(String name) {
			Object oldValue = this.getAttribute(name);
			
			this.data.setAttribute(name);
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(this.data, name, oldValue);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(this.data, name, oldValue);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#setAttribute(java.lang.String, java.lang.Object)
		 */
		public Object setAttribute(String name, Object value) {
			Object oldValue = this.data.setAttribute(name, value);
			
			//	notify listeners
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(this.data, name, oldValue);
					return oldValue;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(this.data, name, oldValue);
			
			return oldValue;
		}
	}
	
	/**	a mutable view of an annotation, behaving relative to the annotation its was retrieved from
	 */
	private class MutableAnnotationView extends QueriableAnnotationView implements MutableAnnotation {
		private ArrayList charListeners = null;
		private ArrayList tokenListeners = null;
		private ArrayList annotationListeners = null;
		MutableAnnotationView(AnnotationBase data, QueriableAnnotation base) {
			super(data, base);
			this.data.views.add(this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.QueriableAnnotationView#finalize()
		 */
		protected void finalize() throws Throwable {
			if (this.data != null)
				this.data.views.remove(this);
			super.finalize();
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addAnnotation(de.gamta.Annotation)
		 */
		public MutableAnnotation addAnnotation(Annotation annotation) {
			AnnotationBase ab = this.data.addAnnotation(annotation);
			
			//	check success
			if (ab == null)
				return null;
			
			//	notify own listeners
			this.notifyAnnotationAdded(ab);
			
			//	return new Annotation
			return new MutableAnnotationView(ab, this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addAnnotation(java.lang.String, int, int)
		 */
		public MutableAnnotation addAnnotation(String type, int startIndex, int size) {
			AnnotationBase ab = this.data.addAnnotation(type, startIndex, size);
			
			//	check success
			if (ab == null)
				return null;
			
			//	notify own listeners
			this.notifyAnnotationAdded(ab);
			
			//	return new Annotation
			return new MutableAnnotationView(ab, this);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#addAnnotation(int, int, java.lang.String)
		 */
		public MutableAnnotation addAnnotation(int startIndex, int endIndex, String type) {
			return this.addAnnotation(type, startIndex, (endIndex - startIndex));
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addChar(char)
		 */
		public void addChar(char ch) {
			this.data.addChar(ch);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addChars(java.lang.CharSequence)
		 */
		public void addChars(CharSequence chars) {
			this.data.addChars(chars);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addTokens(java.lang.CharSequence)
		 */
		public CharSequence addTokens(CharSequence tokens) {
			return this.data.addTokens(tokens);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#clear()
		 */
		public void clear() {
			this.data.clear();
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotation(java.lang.String)
		 */
		public MutableAnnotation getMutableAnnotation(String id) {
			AnnotationBase ab = this.data.getAnnotation(id);
			return ((ab == null) ? null : new MutableAnnotationView(ab, this));
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getMutableAnnotations()
		 */
		public MutableAnnotation[] getMutableAnnotations() {
			return this.getMutableAnnotations(null);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getMutableAnnotations(java.lang.String)
		 */
		public MutableAnnotation[] getMutableAnnotations(String type) {
			AnnotationBase[] abs = this.data.getAnnotations(type);
			MutableAnnotation mas[] = new MutableAnnotation[abs.length];
			for (int a = 0; a < abs.length; a++)
				mas[a] = new MutableAnnotationView(abs[a], this);
			Arrays.sort(mas, nestingOrder);
			return mas;
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsSpanning(int, int)
		 */
		public MutableAnnotation[] getMutableAnnotationsSpanning(int startIndex, int endIndex) {
			return this.getMutableAnnotationsSpanning(null, startIndex, endIndex);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsSpanning(java.lang.String, int, int)
		 */
		public MutableAnnotation[] getMutableAnnotationsSpanning(String type, int startIndex, int endIndex) {
			return wrapAnnotationBasesMutable(this.data.getAnnotationsSpanning(type, startIndex, endIndex), this);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsOverlapping(int, int)
		 */
		public MutableAnnotation[] getMutableAnnotationsOverlapping(int startIndex, int endIndex) {
			return this.getMutableAnnotationsOverlapping(null, startIndex, endIndex);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.MutableAnnotation#getMutableAnnotationsOverlapping(java.lang.String, int, int)
		 */
		public MutableAnnotation[] getMutableAnnotationsOverlapping(String type, int startIndex, int endIndex) {
			return wrapAnnotationBasesMutable(this.data.getAnnotationsOverlapping(type, startIndex, endIndex), this);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#getMutableSubsequence(int, int)
		 */
		public MutableTokenSequence getMutableSubsequence(int start, int size) {
			return this.data.getMutableSubsequence(start, size);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#insertChar(char, int)
		 */
		public void insertChar(char ch, int offset) {
			this.data.insertChar(ch, offset);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#insertChars(java.lang.CharSequence, int)
		 */
		public void insertChars(CharSequence chars, int offset) {
			this.data.insertChars(chars, offset);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#insertTokensAt(java.lang.CharSequence, int)
		 */
		public CharSequence insertTokensAt(CharSequence tokens, int index) {
			return this.data.insertTokensAt(tokens, index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#mutableSubSequence(int, int)
		 */
		public MutableCharSequence mutableSubSequence(int start, int end) {
			return this.data.mutableSubSequence(start, end);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeAnnotation(de.gamta.Annotation)
		 */
		public Annotation removeAnnotation(Annotation annotation) {
			AnnotationBase ab = this.data.removeAnnotation(annotation);
			
			Annotation ra = new TemporaryAnnotation(this, annotation.getType(), annotation.getStartIndex(), annotation.size());
			ra.copyAttributes(ab);
			
			//	annotation did not belong to this document
			if (ab == null) return annotation;
			
			//	notify own listeners
			this.notifyAnnotationRemoved(ab);
			
			//	return standalone annotation otherwise
			return ra;
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeChar(int)
		 */
		public char removeChar(int offset) {
			return this.data.removeChar(offset);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeChars(int, int)
		 */
		public CharSequence removeChars(int offset, int length) {
			return this.data.removeChars(offset, length);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addCharSequenceListener(de.gamta.CharSequenceListener)
		 */
		public void addCharSequenceListener(CharSequenceListener csl) {
			if (csl == null)
				return;
//			if (this.charListeners == null) this.charListeners = new Vector(2);
			if (this.charListeners == null)
				this.charListeners = new ArrayList(2);
			this.charListeners.add(csl);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeCharSequenceListener(de.gamta.CharSequenceListener)
		 */
		public void removeCharSequenceListener(CharSequenceListener csl) {
			if (this.charListeners != null)
				this.charListeners.remove(csl);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeTokens(de.gamta.Annotation)
		 */
		public TokenSequence removeTokens(Annotation annotation) {
			return this.data.removeTokens(annotation);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeTokensAt(int, int)
		 */
		public TokenSequence removeTokensAt(int index, int size) {
			return this.data.removeTokensAt(index, size);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#addTokenSequenceListener(de.gamta.TokenSequenceListener)
		 */
		public void addTokenSequenceListener(TokenSequenceListener tsl) {
			if (tsl == null)
				return;
//			if (this.tokenListeners == null) this.tokenListeners = new Vector(2);
			if (this.tokenListeners == null) 
				this.tokenListeners = new ArrayList(2);
			this.tokenListeners.add(tsl);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#removeTokenSequenceListener(de.gamta.TokenSequenceListener)
		 */
		public void removeTokenSequenceListener(TokenSequenceListener tsl) {
			if (this.tokenListeners != null)
				this.tokenListeners.remove(tsl);
		}
		//	promote a change to the underlying token sequence to listeners listening to this view
		void notifyTokenSequenceChanged(TokenSequenceEvent tse) {
			if ((this.charListeners == null) && (this.tokenListeners == null)) return;
			
			//	produce char sequence event refering to this view
			CharSequenceEvent vCse = new CharSequenceEvent(this, tse.cause.offset, tse.cause.inserted, tse.cause.removed);
			
			if (this.charListeners != null)
				for (int l = 0; l < this.charListeners.size(); l++)
					((CharSequenceListener) this.charListeners.get(l)).charSequenceChanged(vCse);
			
			if (this.tokenListeners == null) return;
			
			//	produce token sequence event refering to this view
			TokenSequenceEvent vTse = new TokenSequenceEvent(this, tse.index, tse.inserted, tse.removed, vCse);
			
			if (this.tokenListeners != null)
				for (int l = 0; l < this.tokenListeners.size(); l++)
					((TokenSequenceListener) this.tokenListeners.get(l)).tokenSequenceChanged(vTse);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#setChar(char, int)
		 */
		public char setChar(char ch, int offset) {
			return this.data.setChar(ch, offset);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#setChars(java.lang.CharSequence, int, int)
		 */
		public CharSequence setChars(CharSequence chars, int offset, int length) {
			return this.data.setChars(chars, offset, length);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#setLeadingWhitespace(java.lang.CharSequence)
		 */
		public CharSequence setLeadingWhitespace(CharSequence whitespace) throws IllegalArgumentException {
			return this.data.setLeadingWhitespace(whitespace);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#setValueAt(java.lang.CharSequence, int)
		 */
		public CharSequence setValueAt(CharSequence value, int index) throws IllegalArgumentException {
			return this.data.setValueAt(value, index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.GamtaDocument.AnnotationBase#setWhitespaceAfter(java.lang.CharSequence, int)
		 */
		public CharSequence setWhitespaceAfter(CharSequence whitespace, int index) throws IllegalArgumentException {
			return this.data.setWhitespaceAfter(whitespace, index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.MutableAnnotation#addAnnotationListener(de.gamta.AnnotationListener)
		 */
		public void addAnnotationListener(AnnotationListener al) {
			if (al == null)
				return;
			if (this.annotationListeners == null)
				this.annotationListeners = new ArrayList(2);
			this.annotationListeners.add(al);
//			if (al != null) {
//				if (this.annotationListeners == null)
//					this.annotationListeners = new Vector(2);
//				this.annotationListeners.add(al);
//			}
		}
		/* (non-Javadoc)
		 * @see de.gamta.MutableAnnotation#removeAnnotationListener(de.gamta.AnnotationListener)
		 */
		public void removeAnnotationListener(AnnotationListener al) {
			if (this.annotationListeners != null)
				this.annotationListeners.remove(al);
		}
		
		void notifyAnnotationAdded(AnnotationBase added) {
			if (this.annotationListeners != null) {
				QueriableAnnotation doc = new ImmutableAnnotation(this);
				Annotation addedAnnotation = new ImmutableAnnotation(new QueriableAnnotationView(added, this));
				for (int l = 0; l < this.annotationListeners.size(); l++)
					((AnnotationListener) this.annotationListeners.get(l)).annotationAdded(doc, addedAnnotation);
			}
			
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAdded(added);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAdded(added);
		}
		
		void notifyAnnotationRemoved(AnnotationBase removed) {
			if (this.annotationListeners != null) {
				QueriableAnnotation doc = new ImmutableAnnotation(this);
				Annotation removedAnnotation = new TemporaryAnnotation(doc, removed.getType(), (removed.absoluteStartIndex - this.getAbsoluteStartIndex()), removed.size);
				removedAnnotation.copyAttributes(removed);
				for (int l = 0; l < this.annotationListeners.size(); l++)
					((AnnotationListener) this.annotationListeners.get(l)).annotationRemoved(doc, removedAnnotation);
			}
			
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationRemoved(removed);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationRemoved(removed);
		}
		
		void notifyAnnotationTypeChanged(AnnotationBase reTyped, String oldType) {
			if ((reTyped != this.data) && (this.annotationListeners != null)) {
				QueriableAnnotation doc = new ImmutableAnnotation(this);
				Annotation reTypedAnnotation = new ImmutableAnnotation(new QueriableAnnotationView(reTyped, this));
				for (int l = 0; l < this.annotationListeners.size(); l++)
					((AnnotationListener) this.annotationListeners.get(l)).annotationTypeChanged(doc, reTypedAnnotation, oldType);
			}
			
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationTypeChanged(reTyped, oldType);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationTypeChanged(reTyped, oldType);
		}
		
		void notifyAnnotationAttributeChanged(AnnotationBase target, String attributeName, Object oldValue) {
			if (this.annotationListeners != null) {
				QueriableAnnotation doc = new ImmutableAnnotation(this);
				Annotation targetAnnotation = new ImmutableAnnotation(new QueriableAnnotationView(target, this));
				for (int l = 0; l < this.annotationListeners.size(); l++)
					((AnnotationListener) this.annotationListeners.get(l)).annotationAttributeChanged(doc, targetAnnotation, attributeName, oldValue);
			}
			
			QueriableAnnotation base = this.base;
			while (base != GamtaDocument.this) {
				if (base instanceof MutableAnnotationView) {
					((MutableAnnotationView) base).notifyAnnotationAttributeChanged(target, attributeName, oldValue);
					return;
				}
				else if (base instanceof QueriableAnnotationView)
					base = ((QueriableAnnotationView) base).base;
				
				else base = GamtaDocument.this;
			}
			if (base == GamtaDocument.this)
				GamtaDocument.this.notifyAnnotationAttributeChanged(target, attributeName, oldValue);
		}
	}
	
	/**	a view on a token, behaving relative to the Annotation it was retrieved from
	 */
	private class TokenView implements Token {
		private Token data; // the actual token to wrap
		private QueriableAnnotationView base; // the annotation this token was retrieved from
		TokenView(Token data, QueriableAnnotationView base) {
			this.data = data;
			this.base = base;
		}
		/* (non-Javadoc)
		 * @see java.lang.Object#equals(java.lang.Object)
		 */
		public boolean equals(Object obj) {
			return this.getValue().equals(obj);
		}
		/* (non-Javadoc)
		 * @see java.lang.Object#hashCode()
		 */
		public int hashCode() {
			return this.getValue().hashCode();
		}
		/* (non-Javadoc)
		 * @see java.lang.Object#toString()
		 */
		public String toString() {
			return this.getValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#charAt(int)
		 */
		public char charAt(int index) {
			return this.data.charAt(index);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#getEndOffset()
		 */
		public int getEndOffset() {
			return (this.data.getEndOffset() - this.base.getAbsoluteStartOffset());
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#getStartOffset()
		 */
		public int getStartOffset() {
			return (this.data.getStartOffset() - this.base.getAbsoluteStartOffset());
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#getTokenizer()
		 */
		public Tokenizer getTokenizer() {
			return this.data.getTokenizer();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#getValue()
		 */
		public String getValue() {
			return this.data.getValue();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#length()
		 */
		public int length() {
			return this.data.length();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Token#subSequence(int, int)
		 */
		public CharSequence subSequence(int start, int end) {
			return this.data.subSequence(start, end);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#clearAttributes()
		 */
		public void clearAttributes() {
			this.data.clearAttributes();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#copyAttributes(de.gamta.Attributed)
		 */
		public void copyAttributes(Attributed source) {
			this.data.copyAttributes(source);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#getAttribute(java.lang.String, java.lang.Object)
		 */
		public Object getAttribute(String name, Object def) {
			return this.data.getAttribute(name, def);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#getAttribute(java.lang.String)
		 */
		public Object getAttribute(String name) {
			return this.data.getAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#getAttributeNames()
		 */
		public String[] getAttributeNames() {
			return this.data.getAttributeNames();
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#hasAttribute(java.lang.String)
		 */
		public boolean hasAttribute(String name) {
			return this.data.hasAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#removeAttribute(java.lang.String)
		 */
		public Object removeAttribute(String name) {
			return this.data.removeAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.uka.ipd.idaho.gamta.Attributed#setAttribute(java.lang.String)
		 */
		public void setAttribute(String name) {
			this.data.setAttribute(name);
		}
		/* (non-Javadoc)
		 * @see de.gamta.Attributed#setAttribute(java.lang.String, java.lang.Object)
		 */
		public Object setAttribute(String name, Object value) {
			return this.data.setAttribute(name, value);
		}
	}
	
	private long nextCreateOrderNumber = 0;
	synchronized long getCreateOrderNumber() {
		return nextCreateOrderNumber++;
	}
	
	//	the basic implementation of an Annotation at this Document, which backs the view(s)
	private class AnnotationBase extends AbstractAttributed {
		String type; // the type of the Annotation, corresponding to the XML element name
		
//		private int absoluteStartIndex; // the index of this Annotation's first token in the TokenSequence of the surrounding GamtaDocument 
//		private int size; // the number of tokens contained in this Annotation
		int absoluteStartIndex; // the index of this Annotation's first token in the TokenSequence of the surrounding GamtaDocument 
		int size; // the number of tokens contained in this Annotation
		
		String annotationId = Gamta.getAnnotationID(); // the ID for this Annotation
		
//		private long timestamp = System.currentTimeMillis(); // creation time stamp, for maintaining insertion order
		final long createOrderNumber = getCreateOrderNumber(); // creation order number, for maintaining insertion order
		
		private Change change = null; // the change originating from the current update to the underlying token sequence (will be null unless a change is in progress)
		
//		private Vector views = new Vector(); // the views currently referring to this AbbotationBase, for event notification purposes
		private ArrayList views = new ArrayList(2); // the views currently referring to this AbbotationBase, for event notification purposes
		
		final AnnotationCache subAnnotationsByType = new AnnotationCache(16);
		
		AnnotationBase(String type, int startIndex, int size) {
//			if ((type == null) || (type.trim().length() == 0))
			if ((type == null) || !AnnotationUtils.isValidAnnotationType(type))
				throw new IllegalArgumentException("'" + type + "' is not a valid Annotation type");
			this.type = type;
			this.absoluteStartIndex = startIndex;
			this.size = size;
		}
		
		void cleanCaches() {
			if (this.subAnnotationsByType.isEmpty())
				return;
			for (Iterator kit = this.subAnnotationsByType.keySet().iterator(); kit.hasNext();) {
				AnnotationCacheEntry ace = ((AnnotationCacheEntry) this.subAnnotationsByType.get(kit.next()));
				if (ace.isInvalid())
					kit.remove();
			}
		}
		
		void clearCaches() {
			this.subAnnotationsByType.clear();
//			this.subAnnotationsByTypeAndRange.clear();
		}
		
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String)
		 */
		public Object getAttribute(String name) {
//			if (SIZE_ATTRIBUTE.equals(name))
//				return new Integer(this.size);
//			else if (ANNOTATION_VALUE_ATTRIBUTE.equals(name))
//				return this.getValue();
//			else if (ANNOTATION_ID_ATTRIBUTE.equals(name))
//				return this.annotationId;
//			else return super.getAttribute(name);
			return this.getAttribute(name, null);
		}
		
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#getAttribute(java.lang.String, java.lang.Object)
		 */
		public Object getAttribute(String name, Object def) {
			if (SIZE_ATTRIBUTE.equals(name))
				return new Integer(this.size);
			else if (ANNOTATION_VALUE_ATTRIBUTE.equals(name))
				return this.getValue();
			else if (ANNOTATION_ID_ATTRIBUTE.equals(name))
				return this.annotationId;
			else return super.getAttribute(name, def);
		}
		
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#hasAttribute(java.lang.String)
		 */
		public boolean hasAttribute(String name) {
			return (START_INDEX_ATTRIBUTE.equals(name) || SIZE_ATTRIBUTE.equals(name) || END_INDEX_ATTRIBUTE.equals(name) || ANNOTATION_VALUE_ATTRIBUTE.equals(name) || ANNOTATION_ID_ATTRIBUTE.equals(name) || super.hasAttribute(name));
		}
		
		/* (non-Javadoc)
		 * @see de.gamta.defaultImplementation.AbstractAttributed#setAttribute(java.lang.String, java.lang.Object)
		 */
		public Object setAttribute(String name, Object value) {
			if (START_INDEX_ATTRIBUTE.equals(name) || SIZE_ATTRIBUTE.equals(name) || END_INDEX_ATTRIBUTE.equals(name) || ANNOTATION_VALUE_ATTRIBUTE.equals(name))
				return value;
			else if (ANNOTATION_ID_ATTRIBUTE.equals(name)) {
				if ((value != null) && (value instanceof String) && (value.toString().trim().length() == this.annotationId.length())) {
					String oldId = this.annotationId;
					this.annotationId = value.toString();
//					annotations.annotationIDs.remove(oldId);
//					annotations.annotationIDs.add(this.annotationId);
					annotations.annotationIdChanged(this, oldId);
					return oldId;
				}
				else return value;
			}
			else return super.setAttribute(name, value);
		}

		//	notify this annotation base of a change in the underlying token sequence, so it can adjust itself
		private static final boolean DEBUG_CHANGE = false;
		final boolean printDebugInfo() {
			return false;
//			return ("page".equalsIgnoreCase(this.type) || (this == modificationSource));
		}
		synchronized void tokenSequeceChanged(TokenSequenceEvent tse) {
			if (DEBUG_CHANGE || this.printDebugInfo()) {
				System.out.println(this.type + " (" + this.absoluteStartIndex + "): Token Sequence Changed at " + tse.index);
				System.out.println("  inserted (" + tse.inserted.size() + ") '" + tse.inserted + "'");
				System.out.println("  removed (" + tse.removed.size() + ") '" + tse.removed + "'");
			}
			
			//	change after the end of this annotation, nothing to do
			if (tse.index > this.getEndIndex()) {
				if (DEBUG_CHANGE || this.printDebugInfo())
					System.out.println("  after end (" + this.getEndIndex() + ")");
				return;
			}
			
			//	change ends before start of this annotation, adjust start index
			if ((tse.index + tse.removed.size()) < this.absoluteStartIndex) {
				this.change = new Change((tse.inserted.size() - tse.removed.size()), 0, null);
				if (DEBUG_CHANGE || this.printDebugInfo())
					System.out.println("  before start (" + this.absoluteStartIndex + ")");
				
				//	we are done
				return;
			}
			
			//	produce events relative to this Annotation to notify listeners on views (views will have to add themselves as the modified sequence, though)
			CharSequenceEvent relCse = null;
			TokenSequenceEvent relTse = null;
			
			//	plain removal
			if (tse.inserted.size() == 0) {
				if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  plain removal");
				
				//	removal starts befor this Annotation
				if (tse.index < this.absoluteStartIndex) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  removal before start");
					
					int removedBefore = (this.absoluteStartIndex - tse.index);
					int removedInside = Math.min((tse.removed.size() - removedBefore), this.size);
					
					TokenSequence removedTokens = tse.removed.getSubsequence(removedBefore, removedInside);
					relCse = new CharSequenceEvent(GamtaDocument.this, 0, "", removedTokens.toString());
					relTse = new TokenSequenceEvent(GamtaDocument.this, 0, null, removedTokens, relCse);
					
					this.change = new Change(-removedBefore, -removedInside, relTse);
				}
				
				//	removal starts at first token
				else if (tse.index == this.absoluteStartIndex) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  removal at start");
					
					int removedInside = Math.min(tse.removed.size(), this.size);
					
					TokenSequence removedTokens = tse.removed.getSubsequence(0, removedInside);
					relCse = new CharSequenceEvent(GamtaDocument.this, 0, "", removedTokens.toString());
					relTse = new TokenSequenceEvent(GamtaDocument.this, 0, null, removedTokens, relCse);
					
					this.change = new Change(0, -removedInside, relTse);
				}
				
				//	removal starts inside this Annotation
				else if (tse.index < this.getEndIndex()) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  removal inside");
					
					int removedInside = Math.min(tse.removed.size(), (this.getEndIndex() - tse.index));
					
					TokenSequence removedTokens = tse.removed.getSubsequence(0, removedInside);
					relCse = new CharSequenceEvent(GamtaDocument.this, (((tse.index == tokenData.size()) ? tokenData.length() : tokenData.tokenAt(tse.index).getStartOffset()) - tokenData.tokenAt(this.absoluteStartIndex).getStartOffset()), "", removedTokens.toString());
					relTse = new TokenSequenceEvent(GamtaDocument.this, (tse.index - this.absoluteStartIndex), null, removedTokens, relCse);
					
					this.change = new Change(0, -removedInside, relTse);
				} // ignore removals at end index
			}
			
			//	plain insertion
			else if (tse.removed.size() == 0) {
				if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  plain insertion");
				
				//	insertion at start index, check nesting
				if (tse.index == this.absoluteStartIndex) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  insertion at start");
					
					//	tokens belong to this Annotation
					if (this.isNestedInThis(modificationSource)) {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  ... and inside");
						
						relCse = new CharSequenceEvent(GamtaDocument.this, 0, tse.inserted.toString(), "");
						relTse = new TokenSequenceEvent(GamtaDocument.this, 0, tse.inserted, null, relCse);
						
						this.change = new Change(0, tse.inserted.size(), relTse);
					}
					
					//	tokens inserted before this annotation
					else {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  ... and actually before start");
						
						this.change = new Change(tse.inserted.size(), 0, null);
					}
				}
				
				//	insertion inside Annotation
				else if (tse.index < this.getEndIndex()) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  insertion inside");
					
					relCse = new CharSequenceEvent(GamtaDocument.this, (tokenData.tokenAt(tse.index).getStartOffset() - tokenData.tokenAt(this.absoluteStartIndex).getStartOffset()), tse.inserted.toString(), "");
					relTse = new TokenSequenceEvent(GamtaDocument.this, 0, tse.inserted, null, relCse);
					
					//	tokens belong to this Annotation
					this.change = new Change(0, tse.inserted.size(), relTse);
				}
				
				//	insertion at end of Annotation, check nesting
				else {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  insertion at end");
					
					//	tokens appended to this Annotation, or a nested one
					if (this.isNestedInThis(modificationSource)) {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  ... and inside");
						
						relCse = new CharSequenceEvent(GamtaDocument.this, (tokenData.tokenAt(tse.index).getStartOffset() - tokenData.tokenAt(this.absoluteStartIndex).getStartOffset()), tse.inserted.toString(), "");
						relTse = new TokenSequenceEvent(GamtaDocument.this, 0, tse.inserted, null, relCse);
						
						this.change = new Change(0, tse.inserted.size(), relTse);
					}
					
					//	ignore the change otherwise
					else if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  ... and actually after end");
				}
			}
			
			//	replacement before start of Annotation
			else if ((tse.index + tse.removed.size()) == this.absoluteStartIndex) {
				if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement before");
				this.change = new Change((tse.inserted.size() - tse.removed.size()), 0, null);
			}
			
			//	replacement after end of Annotation
			else if (tse.index == this.getEndIndex()) {
				//	ignore replacement
				if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement after");
			}
			
			//	replacement of tokens of Annotation
			else {
				if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement overlapping");
				
				//	replacement spans both borders of Annotation, remove it
				if ((tse.index < this.absoluteStartIndex) && ((tse.index + tse.removed.size()) > this.getEndIndex())) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement covering");
					
					int removedBefore = (this.absoluteStartIndex - tse.index);
					
					TokenSequence removedTokens = tse.removed.getSubsequence(removedBefore, this.size);
					relCse = new CharSequenceEvent(GamtaDocument.this, 0, "", removedTokens.toString());
					relTse = new TokenSequenceEvent(GamtaDocument.this, 0, null, removedTokens, relCse);
					
					this.change = new Change((tse.index - this.absoluteStartIndex), -this.size, relTse);
				}
				
				//	replacement completely inside Annotation
				else if ((tse.index >= this.absoluteStartIndex) && ((tse.index + tse.removed.size()) <= this.getEndIndex())) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement inside");
					
					int relOffset = tse.cause.offset - this.getAbsoluteStartOffset();
					
					relCse = new CharSequenceEvent(GamtaDocument.this, relOffset, tse.cause.inserted, tse.cause.removed);
					relTse = new TokenSequenceEvent(GamtaDocument.this, (tse.index - this.absoluteStartIndex), tse.inserted, tse.removed, relCse);
					
					this.change = new Change(0, (tse.inserted.size() - tse.removed.size()), relTse);
				}
				
				//	replacement spans start of Annotation
				else if (tse.index < this.absoluteStartIndex) {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement over start");
					
					//	insertion ends before Annotation, we've lost some tokens
					if ((tse.index + tse.inserted.size()) <= this.absoluteStartIndex) {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  tokens cut at start");
						
						int removedBefore = (this.absoluteStartIndex - tse.index);
						int removedInside = Math.min((tse.removed.size() - removedBefore), this.size);
						
						TokenSequence removedTokens = tse.removed.getSubsequence(removedBefore, removedInside);
						relCse = new CharSequenceEvent(GamtaDocument.this, 0, null, removedTokens.toString());
						relTse = new TokenSequenceEvent(GamtaDocument.this, 0, null, removedTokens, relCse);
						
						this.change = new Change((tse.inserted.size() - removedBefore), -removedInside, relTse);
					}
					
					//	tokens before and inside Annotation replaced
					else {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  tokens changed over start");
						
						int changedBefore = (this.absoluteStartIndex - tse.index);
						int removedInside = Math.min((tse.removed.size() - changedBefore), this.size);
						int insertedInside = (tse.inserted.size() - changedBefore);
						
						TokenSequence removedTokens = tse.removed.getSubsequence(changedBefore, removedInside);
						TokenSequence insertedTokens = tse.inserted.getSubsequence(changedBefore, insertedInside);
						relCse = new CharSequenceEvent(GamtaDocument.this, 0, insertedTokens.toString(), removedTokens.toString());
						relTse = new TokenSequenceEvent(GamtaDocument.this, 0, insertedTokens, removedTokens, relCse);
						
						this.change = new Change(0, (insertedInside - removedInside), relTse);
					}
				}
				
				//	replacement spans end of Annotation
				else {
					if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  replacement over end");
					
					//	insertion ends before end of Annotation, we've been added some tokens
					if ((tse.index + tse.inserted.size()) <= this.getEndIndex()) {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  tokens inserted at end");
						
						int relOffset = (tokenData.tokenAt(tse.index).getStartOffset() - this.getAbsoluteStartOffset());
						int relIndex = (tse.index - this.absoluteStartIndex);
						int removedInside = (this.size - relIndex);
						int insertedInside = Math.min(tse.inserted.size(), removedInside);
						
						TokenSequence removedTokens = tse.removed.getSubsequence(0, removedInside);
						TokenSequence insertedTokens = tse.inserted.getSubsequence(0, insertedInside);
						relCse = new CharSequenceEvent(GamtaDocument.this, relOffset, insertedTokens.toString(), removedTokens.toString());
						relTse = new TokenSequenceEvent(GamtaDocument.this, relIndex, insertedTokens, removedTokens, relCse);
						
						this.change = new Change(0, (insertedInside - removedInside), relTse);
					}
					
					//	tokens inside and after Annotation replaced
					else {
						if (DEBUG_CHANGE || this.printDebugInfo()) System.out.println("  tokens changed over end");
						
						int relOffset = (tokenData.tokenAt(tse.index).getStartOffset() - this.getAbsoluteStartOffset());
						int relIndex = (tse.index - this.absoluteStartIndex);
						int changedInside = (this.size - relIndex);
						
						TokenSequence removedTokens = tse.removed.getSubsequence(0, changedInside);
						TokenSequence insertedTokens = tse.inserted.getSubsequence(0, changedInside);
						relCse = new CharSequenceEvent(GamtaDocument.this, relOffset, insertedTokens.toString(), removedTokens.toString());
						relTse = new TokenSequenceEvent(GamtaDocument.this, relIndex, insertedTokens, removedTokens, relCse);
						
						this.change = new Change(0, 0, relTse);
					}
				}
			}
		}
		
		//	check if some AnnotationBase is nested in this one
		boolean isNestedInThis(AnnotationBase ab) {
			if (ab == this)
				return true;
			if (ab == null)
				return false;
			if (ab.absoluteStartIndex < this.absoluteStartIndex)
				return false;
			if (ab.getEndIndex() > this.getEndIndex())
				return false;
			if ((ab.absoluteStartIndex == this.absoluteStartIndex) && (ab.size == this.size)) {
//				int ano = annotationNestingOrder.getNestingOrder(this.type, ab.type);
				int ano = typeNestingOrder.compare(this.type, ab.type);
//				if (ano == 0)
//					return (this.timestamp < ab.timestamp);
				if (ano == 0)
					return (this.createOrderNumber < ab.createOrderNumber);
				else return (ano < 0);
			}
			return true;
		}
		
		//	apply last change to indices
		void commitChange() {
			if (this.change == null)
				return;
			
			this.absoluteStartIndex += this.change.startIndexDelta;
			this.size += this.change.sizeDelta;
			
			if (this.change.cause != null)
				for (int v = 0; v < this.views.size(); v++)
					((MutableAnnotationView) this.views.get(v)).notifyTokenSequenceChanged(this.change.cause);
			
			this.change = null;
		}
		
		//	representation of a change to this annotation from the time it is computed until the time it is committed
		private class Change {
			final int startIndexDelta;
			final int sizeDelta;
			final TokenSequenceEvent cause;
			Change(int startIndexDelta, int sizeDelta, TokenSequenceEvent cause) {
				this.startIndexDelta = startIndexDelta;
				this.sizeDelta = sizeDelta;
				this.cause = cause;
			}
		}
		String changeTypeTo(String newType) {
//			if ((newType == null) || (newType.trim().length() == 0))
			if ((newType == null) || !AnnotationUtils.isValidAnnotationType(newType))
				throw new IllegalArgumentException("'" + newType + "' is not a valid Annotation type");
			String oldType = this.type;
			this.type = newType;
			annotations.annotationTypeChanged(this, oldType);
			return oldType;
		}
		String getAnnotationID() {
			return this.annotationId;
		}
		int getEndIndex() {
			return (this.absoluteStartIndex + this.size);
		}
		String getType() {
			return this.type;
		}
		int getEndOffset() {
			return this.lastToken().getEndOffset();
		}
		void addChar(char ch) {
			modificationSource = this;
			tokenData.insertChar(ch, this.getEndOffset());
			modificationSource = null;
		}
		void addChars(CharSequence chars) {
			modificationSource = this;
			tokenData.insertChars(chars, this.getEndOffset());
			modificationSource = null;
		}
		CharSequence addTokens(CharSequence tokens) {
			modificationSource = this;
			CharSequence cs = tokenData.insertTokensAt(tokens, this.getEndIndex());
			modificationSource = null;
			return cs;
		}
		char charAt(int index) {
			if (index >= this.length())
				throw new IndexOutOfBoundsException("" + index + " >= " + this.length());
			return tokenData.charAt(index + this.getAbsoluteStartOffset());
		}
		void clear() {
			modificationSource = this;
			tokenData.removeTokensAt(this.absoluteStartIndex, this.size);
			modificationSource = null;
		}
		protected void finalize() throws Throwable {
			
			//	clean up views
			for (int v = 0; v < this.views.size(); v++) {
				MutableAnnotationView mav = ((MutableAnnotationView) this.views.get(v));
				if (mav.charListeners != null)
					mav.charListeners.clear();
				if (mav.tokenListeners != null)
					mav.tokenListeners.clear();
				mav.data = null;
			}
			
			//	clear attributes
			this.clearAttributes();
			
			//	discart views
			this.views.clear();
		}
		Token firstToken() {
			return tokenData.tokenAt(this.absoluteStartIndex);
		}
		String firstValue() {
			return tokenData.valueAt(this.absoluteStartIndex);
		}
		String getLeadingWhitespace() {
			return "";
		}
		MutableTokenSequence getMutableSubsequence(int start, int size) {
			if ((start + size) > this.size)
				throw new IndexOutOfBoundsException("" + start + "+" + size + " > " + this.size);
			return tokenData.getMutableSubsequence((start + this.absoluteStartIndex), size);
		}
		TokenSequence getSubsequence(int start, int size) {
			if ((start + size) > this.size)
				throw new IndexOutOfBoundsException("" + start + "+" + size + " > " + this.size);
			return tokenData.getSubsequence((start + this.absoluteStartIndex), size);
		}
		Tokenizer getTokenizer() {
			return tokenData.getTokenizer();
		}
		String getWhitespaceAfter(int index) {
			if (index >= this.size)
				throw new IndexOutOfBoundsException("" + index + " >= " + this.size);
			if ((index + 1) == this.size)
				return "";
			return tokenData.getWhitespaceAfter(index + this.absoluteStartIndex);
		}
		void insertChar(char ch, int offset) {
//			if (offset > this.length())
//				throw new IndexOutOfBoundsException("" + offset + " > " + this.length());
			//	allow char modification in whitespace after last token
			if (offset > (this.length() + tokenData.getWhitespaceAfter(this.absoluteStartIndex + this.size - 1).length()))
				throw new IndexOutOfBoundsException("" + offset + " > " + this.length());
			modificationSource = this;
			tokenData.insertChar(ch, (offset + this.getAbsoluteStartOffset()));
			modificationSource = null;
		}
		void insertChars(CharSequence chars, int offset) {
//			if (offset > this.length())
//				throw new IndexOutOfBoundsException("" + offset + " > " + this.length());
			//	allow char modification in whitespace after last token
			if (offset > (this.length() + tokenData.getWhitespaceAfter(this.absoluteStartIndex + this.size - 1).length()))
				throw new IndexOutOfBoundsException("" + offset + " > " + this.length());
			modificationSource = this;
			tokenData.insertChars(chars, (offset + this.getAbsoluteStartOffset()));
			modificationSource = null;
		}
		CharSequence insertTokensAt(CharSequence tokens, int index) {
			if (index > this.size)
				throw new IndexOutOfBoundsException("" + index + " > " + this.size);
			modificationSource = this;
			CharSequence ch = tokenData.insertTokensAt(tokens, (index + this.absoluteStartIndex));
			modificationSource = null;
			return ch;
		}
		Token lastToken() {
			return tokenData.tokenAt(this.absoluteStartIndex + this.size - 1);
		}
		String lastValue() {
			return tokenData.valueAt(this.absoluteStartIndex + this.size - 1);
		}
		int length() {
			return (this.getEndOffset() - this.getAbsoluteStartOffset());
		}
		char removeChar(int offset) {
			if ((offset + 1) > this.length())
				throw new IndexOutOfBoundsException("" + offset + "+" + 1 + " > " + this.length());
			modificationSource = this;
			char c = tokenData.removeChar(offset + this.getAbsoluteStartOffset());
			modificationSource = null;
			return c;
		}
		CharSequence removeChars(int offset, int length) {
			if ((offset + length) > this.length())
				throw new IndexOutOfBoundsException("" + offset + "+" + length + " > " + this.length());
			modificationSource = this;
			CharSequence ch = tokenData.removeChars((offset + this.getAbsoluteStartOffset()), length);
			modificationSource = null;
			return ch;
		}
		TokenSequence removeTokensAt(int index, int size) {
			if ((index + size) > this.size)
				throw new IndexOutOfBoundsException("" + index + "+" + size + " > " + this.size);
			modificationSource = this;
			TokenSequence ts = tokenData.removeTokensAt((index + this.absoluteStartIndex), size);
			modificationSource = null;
			return ts;
		}
		char setChar(char ch, int offset) {
//			if ((offset + 1) > this.length())
//				throw new IndexOutOfBoundsException("" + offset + "+" + 1 + " > " + this.length());
			//	allow char modification in whitespace after last token
			if ((offset + 1) > (this.length() + tokenData.getWhitespaceAfter(this.absoluteStartIndex + this.size - 1).length()))
				throw new IndexOutOfBoundsException("" + offset + "+" + 1 + " > " + this.length());
			modificationSource = this;
			char c = tokenData.setChar(ch, (offset + this.getAbsoluteStartOffset()));
			modificationSource = null;
			return c;
		}
		CharSequence setChars(CharSequence chars, int offset, int length) {
//			if ((offset + length) > this.length())
//				throw new IndexOutOfBoundsException("" + offset + "+" + length + " > " + this.length());
			//	allow char modification in whitespace after last token
			if ((offset + length) > (this.length() + tokenData.getWhitespaceAfter(this.absoluteStartIndex + this.size - 1).length()))
				throw new IndexOutOfBoundsException("" + offset + "+" + length + " > " + this.length());
			modificationSource = this;
			CharSequence cs = tokenData.setChars(chars, (offset + this.getAbsoluteStartOffset()), length);
			modificationSource = null;
			return cs;
		}
		CharSequence setLeadingWhitespace(CharSequence whitespace) throws IllegalArgumentException {
			//	an annotation always starts at the first token and ends after the last one
			return "";
		}
		CharSequence setValueAt(CharSequence value, int index) throws IllegalArgumentException {
			if (index >= this.size)
				throw new IndexOutOfBoundsException("" + index + " >= " + this.size);
			modificationSource = this;
			CharSequence cs = tokenData.setValueAt(value, (index + this.absoluteStartIndex));
			modificationSource = null;
			return cs;
		}
		CharSequence setWhitespaceAfter(CharSequence whitespace, int index) throws IllegalArgumentException {
			if (index >= this.size)
				throw new IndexOutOfBoundsException("" + index + " >= " + this.size);
//			else if ((index+1) == this.size)
//				return whitespace;
			modificationSource = this;
			CharSequence cs = tokenData.setWhitespaceAfter(whitespace, (index + this.absoluteStartIndex));
			modificationSource = null;
			return cs;
		}
//		int size() {
//			return this.size;
//		}
		CharSequence subSequence(int start, int end) {
			if (start < 0)
				throw new IndexOutOfBoundsException("" + start + " < " + 0);
			else if (end > this.length())
				throw new IndexOutOfBoundsException("" + end + " > " + this.length());
			return tokenData.subSequence((start + this.getAbsoluteStartOffset()), (end + this.getAbsoluteStartOffset()));
		}
		Token tokenAt(int index) {
			if (index >= this.size)
				throw new IndexOutOfBoundsException("" + index + " >= " + this.size);
			return tokenData.tokenAt(index + this.absoluteStartIndex);
		}
		String getValue() {
			return tokenData.subSequence(this.getAbsoluteStartOffset(), this.getEndOffset()).toString();
		}
		String valueAt(int index) {
			if (index >= this.size)
				throw new IndexOutOfBoundsException("" + index + " >= " + this.size);
			return tokenData.valueAt(index + this.absoluteStartIndex);
		}
		MutableCharSequence mutableSubSequence(int start, int end) {
			if (start < 0)
				throw new IndexOutOfBoundsException("" + start + " < " + 0);
			else if (end > this.length())
				throw new IndexOutOfBoundsException("" + end + " > " + this.length());
			return tokenData.mutableSubSequence((start + this.getAbsoluteStartOffset()), (end + this.getAbsoluteStartOffset()));
		}
//		int getAbsoluteStartIndex() {
//			return this.absoluteStartIndex;
//		}
		int getAbsoluteStartOffset() {
			return this.firstToken().getStartOffset();
		}
		AnnotationBase getAnnotation(String id) {
			AnnotationBase ab = annotations.getAnnotation(id);
			if (ab == null)
				return null;
			if (ab.absoluteStartIndex < this.absoluteStartIndex)
				return null;
			if (this.getEndIndex() < ab.getEndIndex())
				return null;
			return ab;
		}
		AnnotationBase[] getAnnotations(String type) {
			return annotations.getAnnotations(this, type);
		}
		AnnotationBase[] getAnnotationsSpanning(String type, int startIndex, int endIndex) {
			return annotations.getAnnotationsSpanning(this, type, startIndex, endIndex);
		}
		AnnotationBase[] getAnnotationsOverlapping(String type, int startIndex, int endIndex) {
			return annotations.getAnnotationsOverlapping(this, type, startIndex, endIndex);
		}
		String[] getAnnotationTypes() {
			return annotations.getAnnotationTypes(this);
		}
		AnnotationBase addAnnotation(Annotation annotation) {
			
			//	check parameter
			if (annotation == null)
				return null;
			
			//	create Annotation
			AnnotationBase ab = this.addAnnotationAbsolute(annotation.getType(), annotation.getStartIndex(), annotation.size());
			
			//	copy attributes
			if (ab != null)
				ab.copyAttributes(annotation);
			
			//	return Annotation
			return ab;
		}
		AnnotationBase addAnnotation(String type, int startIndex, int size) {
			
			//	create Annotation
			AnnotationBase ab = this.addAnnotationAbsolute(type, startIndex, size);
			
			//	return Annotation
			return ab;
		}
		AnnotationBase addAnnotationAbsolute(String type, int startIndex, int size) {
			
			//	check parameters
			if ((startIndex < 0) || (size < 1))
				return null;
			
			//	create Annotation
			AnnotationBase ab = new AnnotationBase(type, (startIndex + this.absoluteStartIndex), size);
			annotations.storeAnnotation(ab);
			
			//	return Annotation
			return ab;
		}
		AnnotationBase removeAnnotation(Annotation annotation) {
//			return annotations.removeAnnotation(this.absoluteStartIndex, annotation);
			return annotations.removeAnnotation(annotation);
		}
		TokenSequence removeTokens(Annotation annotation) {
			modificationSource = this;
			TokenSequence ts = this.removeTokensAt(annotation.getStartIndex(), annotation.size());
			modificationSource = null;
			return ts;
		}
		int compareTo(AnnotationBase ab) {
			int c = (this.absoluteStartIndex - ab.absoluteStartIndex);
			if (c != 0)
				return c;
			c = (ab.size - this.size);
			if (c != 0)
				return c;
//			c = annotationNestingOrder.getNestingOrder(this.type, ab.type);
			if (!this.type.equals(ab.type)) {
				c = typeNestingOrder.compare(this.type, ab.type);
				if (c != 0)
					return c;
			}
//			return ((int) (this.timestamp - ab.timestamp));
			return ((int) (this.createOrderNumber - ab.createOrderNumber));
		}
	}
	private Comparator annotationBaseOrder = new Comparator() {
		public int compare(Object obj1, Object obj2) {
			AnnotationBase ab1 = ((AnnotationBase) obj1);
			AnnotationBase ab2 = ((AnnotationBase) obj2);
			return ab1.compareTo(ab2);
		}
	};
	
	QueriableAnnotation[] wrapAnnotationBasesQueriable(AnnotationBase[] abs, QueriableAnnotation base) {
		QueriableAnnotation qas[] = new QueriableAnnotation[abs.length];
		for (int a = 0; a < abs.length; a++)
			qas[a] = new QueriableAnnotationView(abs[a], base);
		Arrays.sort(qas, nestingOrder);
		return qas;
	}
	
	MutableAnnotation[] wrapAnnotationBasesMutable(AnnotationBase[] abs, MutableAnnotation base) {
		MutableAnnotation mas[] = new MutableAnnotation[abs.length];
		for (int a = 0; a < abs.length; a++)
			mas[a] = new MutableAnnotationView(abs[a], base);
		Arrays.sort(mas, nestingOrder);
		return mas;
	}
	
//	private class AnnotationList {
//		private ArrayList annotations = new ArrayList();
//		private HashSet removed = new HashSet();
//		private int modCount = 0;
//		private int addCount = 0;
//		private int cleanAddCount = 0;
//		private int typeModCount = 0;
//		private int cleanTypeModCount = 0;
//		private int cleanOrderModCount = orderModCount;
//		private final String type;
//		AnnotationList(String type) {
//			this.type = type;
//		}
//		void addAnnotation(AnnotationBase ab) {
//			if (ab == null)
//				return;
//			this.removed.remove(ab);
//			this.annotations.add(ab);
//			this.modCount++;
//			this.addCount++;
//		}
//		void removeAnnotation(AnnotationBase ab) {
//			if (ab == null)
//				return;
//			this.removed.add(ab);
//			this.modCount++;
//		}
//		AnnotationBase getAnnotation(int index) {
//			this.ensureSorted();
//			return ((AnnotationBase) this.annotations.get(index));
//		}
//		boolean isEmpty() {
//			return (this.annotations.isEmpty() || this.removed.containsAll(this.annotations));
//		}
//		int size() {
//			this.ensureClean();
//			return this.annotations.size();
//		}
//		AnnotationBase[] getAnnotations() {
//			this.ensureSorted();
//			return ((AnnotationBase[]) this.annotations.toArray(new AnnotationBase[this.annotations.size()]));
//		}
//		AnnotationBase[] getAnnotationsIn(AnnotationBase base) {
//			
//			//	check cache first
//			AnnotationCacheEntry annots = ((AnnotationCacheEntry) base.subAnnotationListsByType.get(this.type));
//			if ((annots != null) && annots.isValid(this))
//				return annots.annotations;
//			
//			//	make sure we're good to go
//			this.ensureSorted();
//			
//			//	binary search first annotation
//			int left = 0;
//			int right = this.annotations.size();
//			
//			//	narrow staring point with binary search down to a 2 interval
//			int start = -1;
//			for (int c; (start == -1) && ((right - left) > 2);) {
//				int middle = ((left + right) / 2);
//				
//				//	start linear search if interval down to 4
//				if ((right - left) < 4)
//					c = 0;
//				else c = (((AnnotationBase) this.annotations.get(middle)).absoluteStartIndex - base.absoluteStartIndex);
//				
//				if (c < 0)
//					left = middle; // starting point is right of middle
//				else if (c == 0) { // start of Annotation at middle is equal to base start of base, scan leftward for others at same start
//					start = middle;
//					while ((start != 0) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex == base.absoluteStartIndex))
//						start --; // count down to 0 at most
//				}
//				else right = middle;  // starting point is left of middle
//			}
//			
//			//	ensure valid index
//			start = Math.max(start, 0);
//			
//			//	move right to exact staring point
//			while ((start < this.annotations.size()) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex < base.absoluteStartIndex))
//				start++;
//			
//			int baseEndIndex = base.getEndIndex();
//			ArrayList annotList = new ArrayList();
//			for (int a = start; a < this.annotations.size(); a++) {
//				AnnotationBase ab = ((AnnotationBase) this.annotations.get(a));
//				if ((base.absoluteStartIndex <= ab.absoluteStartIndex) && (ab.getEndIndex() <= baseEndIndex))
//					annotList.add(ab);
//				if (baseEndIndex <= ab.absoluteStartIndex)
//					break;
//			}
//			annots = new AnnotationCacheEntry(((AnnotationBase[]) annotList.toArray(new AnnotationBase[annotList.size()])), this);
//			base.subAnnotationListsByType.put(this.type, annots);
//			return annots.annotations;
//		}
//		void cleanup() {
//			for (int a = 0; a < this.annotations.size(); a++) {
//				AnnotationBase ab = ((AnnotationBase) this.annotations.get(a));
//				if (ab.size <= 0) {
//					this.removed.add(ab);
//					this.modCount++;
//					if (AnnotationBase.DEBUG_CHANGE || ab.printDebugInfo())
//						System.out.println("REMOVED: " + ab.type + " at " + ab.absoluteStartIndex + " sized " + ab.size);
//				}
//			}
//			this.ensureClean();
//		}
//		void clear() {
//			this.annotations.clear();
//			this.removed.clear();
//			this.modCount++;
//		}
//		void annotationTypeChanged() {
//			this.typeModCount++;
//		}
//		private void ensureSorted() {
//			this.ensureClean();
//			if ((this.cleanAddCount == this.addCount) && (this.cleanTypeModCount == this.typeModCount) && (this.cleanOrderModCount == orderModCount))
//				return;
//			/* TODOnot if order and types unmodified, we can even save sorting the whole list:
//			 * - sort only added annotations ...
//			 * - ... and then merge them into main list in single pass
//			 * ==> but then, TimSort already does pretty much that ... */
//			Collections.sort(this.annotations, annotationBaseOrder);
//			this.cleanAddCount = this.addCount;
//			this.cleanTypeModCount = this.typeModCount;
//			this.cleanOrderModCount = orderModCount;
//		}
//		private void ensureClean() {
//			if (this.removed.isEmpty())
//				return;
//			for (int a = (this.annotations.size()-1); a >= 0; a--) {
//				if (this.removed.contains(this.annotations.get(a)))
//					this.annotations.remove(a);
//			}
//			this.removed.clear();
//		}
//	}
	
	private static class CacheCleaningTrigger {
		private AnnotationList annotList;
		CacheCleaningTrigger(AnnotationList annotList) {
			this.annotList = annotList;
		}
		protected void finalize() throws Throwable {
			this.annotList.cleanCaches();
		}
	}
	private static class CacheClearingTrigger {
		private AnnotationList annotList;
		CacheClearingTrigger(AnnotationList annotList) {
			this.annotList = annotList;
		}
		protected void finalize() throws Throwable {
			this.annotList.clearCaches();
		}
	}
	
	private class AnnotationList {
		/* Handling our own array saves lots of method calls to ArrayList,
		 * enables more efficient single-pass cleanup (without shifting the
		 * array by one for each removed element), and and saves allocating
		 * many new arrays (mainly for sorting, as Collections.sort() uses
		 * toArray() internally). */
		private AnnotationBase[] annots = new AnnotationBase[16];
		private int annotCount = 0;
		private HashSet removed = new HashSet();
		private int modCount = 0;
		private int addCount = 0;
		private int cleanAddCount = 0;
		private int typeModCount = 0;
		private int cleanTypeModCount = 0;
		private int cleanOrderModCount = orderModCount;
		private final String type;
		
		private int maxAnnotSize = 0;
		private SoftReference cacheCleaningTrigger;
		private SoftReference cacheClearingTrigger;
		
		AnnotationList(String type) {
			this.type = type;
			this.cacheCleaningTrigger = new SoftReference(new CacheCleaningTrigger(this));
			this.cacheClearingTrigger = new SoftReference(new CacheClearingTrigger(this));
		}
		void addAnnotation(AnnotationBase ab) {
			if (ab == null)
				return;
			this.removed.remove(ab);
			if (this.annotCount == this.annots.length) {
				AnnotationBase[] annots = new AnnotationBase[this.annots.length * 2];
				System.arraycopy(this.annots, 0, annots, 0, this.annots.length);
				this.annots = annots;
			}
			this.annots[this.annotCount++] = ab;
			if (this.maxAnnotSize < ab.size)
				this.maxAnnotSize = ab.size;
			this.modCount++;
			this.addCount++;
			this.cacheClearingTrigger.get(); // touch clearing trigger, so cleaning trigger gets reclaimed first
		}
		void removeAnnotation(AnnotationBase ab) {
			if (ab == null)
				return;
			this.removed.add(ab);
			this.modCount++;
			this.cacheClearingTrigger.get(); // touch clearing trigger, so cleaning trigger gets reclaimed first
		}
		AnnotationBase getAnnotation(int index) {
			this.ensureSorted();
			return this.annots[index];
		}
		boolean isEmpty() {
			if (this.annotCount == 0)
				return true;
			for (int a = 0; a < this.annotCount; a++)
				if (!this.removed.contains(this.annots[a]))
					return false;
			return true;
		}
		int size() {
			this.ensureClean();
			return this.annotCount;
		}
		AnnotationBase[] getAnnotations() {
			this.ensureSorted();
			return Arrays.copyOfRange(this.annots, 0, this.annotCount);
		}
		AnnotationBase[] getAnnotations(int maxAbsoluteStartIndex, int minAbsoluteEndIndex) {
			//	no use caching ranges, way too little chance of cache hits
			int minAbsoluteStartIndex = Math.max(0, (minAbsoluteEndIndex - this.maxAnnotSize));
			int maxAbsoluteEndIndex = Math.min(GamtaDocument.this.size(), (maxAbsoluteStartIndex + this.maxAnnotSize));
			return this.getAnnotationsIn(minAbsoluteStartIndex, maxAbsoluteStartIndex, minAbsoluteEndIndex, maxAbsoluteEndIndex);
		}
		AnnotationBase[] getAnnotationsIn(AnnotationBase base) {
			AnnotationCacheEntry annots = base.subAnnotationsByType.lookup(this.type);
			if ((annots == null) || annots.isInvalid(this)) /* cache miss, or entry stale */ {
				AnnotationBase[] abs = this.getAnnotationsIn(base.absoluteStartIndex, (base.getEndIndex()-1), (base.absoluteStartIndex+1), base.getEndIndex());
				annots = new AnnotationCacheEntry(abs, this);
				base.subAnnotationsByType.cache(this.type, annots);
			}
			return annots.annotations;
		}
		AnnotationBase[] getAnnotationsIn(AnnotationBase base, int maxRelativeStartIndex, int minRelativeEndIndex) {
			
			//	get all contained annotations (no use caching ranges, way too little chance of cache hits)
			AnnotationBase[] allAnnots = this.getAnnotationsIn(base);
			
			//	make indexes absolute
			int maxAbsoluteStartIndex = (base.absoluteStartIndex + maxRelativeStartIndex);
			int minAbsoluteEndIndex = (base.absoluteStartIndex + minRelativeEndIndex);
			
			//	get qualifying annotations
			ArrayList annotList = new ArrayList();
			for (int a = 0; a < allAnnots.length; a++) {
				if (maxAbsoluteStartIndex < allAnnots[a].absoluteStartIndex)
					break;
				if (minAbsoluteEndIndex <= allAnnots[a].getEndIndex())
					annotList.add(this.annots[a]);
			}
			return ((AnnotationBase[]) annotList.toArray(new AnnotationBase[annotList.size()]));
		}
		private AnnotationBase[] getAnnotationsIn(int minAbsoluteStartIndex, int maxAbsoluteStartIndex, int minAbsoluteEndIndex, int maxAbsoluteEndIndex) {
			
			//	make sure we're good to go
			this.ensureSorted();
			
			//	binary search first annotation
			int left = 0;
			int right = this.annotCount;
			
			//	narrow staring point with binary search down to a 2 interval
			int start = -1;
			for (int c; (start == -1) && ((right - left) > 2);) {
				int middle = ((left + right) / 2);
				
				//	start linear search if interval down to 4
				if ((right - left) < 4)
					c = 0;
				else c = (this.annots[middle].absoluteStartIndex - minAbsoluteStartIndex);
				
				if (c < 0)
					left = middle; // starting point is right of middle
				else if (c == 0) { // start of Annotation at middle is equal to base start of base, scan leftward for others at same start
					start = middle;
					while ((start != 0) && (minAbsoluteStartIndex <= this.annots[start].absoluteStartIndex))
						start --; // count down to 0 at most
				}
				else right = middle; // starting point is left of middle
			}
			
			//	ensure valid index
			start = Math.max(start, 0);
			
			//	move right to exact staring point
			while ((start < this.annotCount) && (this.annots[start].absoluteStartIndex < minAbsoluteStartIndex))
				start++;
			
			//	move left to exact staring point
			while ((start != 0) && (minAbsoluteStartIndex <= this.annots[start-1].absoluteStartIndex))
				start--;
			
			//	collect and return matching annotations
			ArrayList annotList = new ArrayList();
			for (int a = start; a < this.annotCount; a++) {
				if (maxAbsoluteStartIndex < this.annots[a].absoluteStartIndex) // to right of last potential match
					break;
				if ((minAbsoluteEndIndex <= this.annots[a].getEndIndex()) && (this.annots[a].getEndIndex() <= maxAbsoluteEndIndex)) // end index in range, we have a match
					annotList.add(this.annots[a]);
			}
			return ((AnnotationBase[]) annotList.toArray(new AnnotationBase[annotList.size()]));
		}
		void cleanup() {
			for (int a = 0; a < this.annotCount; a++)
				if (this.annots[a].size <= 0) {
					this.removed.add(this.annots[a]);
					this.modCount++;
					if (AnnotationBase.DEBUG_CHANGE || this.annots[a].printDebugInfo())
						System.out.println("REMOVED: " + this.annots[a].type + " at " + this.annots[a].absoluteStartIndex + " sized " + this.annots[a].size);
				}
			this.ensureClean();
		}
		void clear() {
			Arrays.fill(this.annots, 0, this.annotCount, null); // free up references to help GC
			this.annotCount = 0;
			this.removed.clear();
			this.modCount++;
		}
		void annotationTypeChanged() {
			this.typeModCount++;
		}
		private void ensureSorted() {
			this.ensureClean();
			if ((this.cleanAddCount == this.addCount) && (this.cleanTypeModCount == this.typeModCount) && (this.cleanOrderModCount == orderModCount))
				return;
			/* TODOnot if order and types unmodified, we can even save sorting the whole list:
			 * - sort only added annotations ...
			 * - ... and then merge them into main list in single pass
			 * ==> but then, TimSort already does pretty much that ... */
			Arrays.sort(this.annots, 0, this.annotCount, annotationBaseOrder);
			this.cleanAddCount = this.addCount;
			this.cleanTypeModCount = this.typeModCount;
			this.cleanOrderModCount = orderModCount;
		}
		private void ensureClean() {
			this.cacheClearingTrigger.get(); // touch clearing trigger, so cleaning trigger gets reclaimed first
			if (this.removed.isEmpty())
				return;
			int removed = 0;
			int maxAnnotSize = 0;
			for (int a = 0; a < this.annotCount; a++) {
				if (this.removed.contains(this.annots[a]))
					removed++;
				else {
					this.annots[a].subAnnotationsByType.remove(this.type); // cache entries are invalid now
					if (maxAnnotSize < this.annots[a].size)
						maxAnnotSize = this.annots[a].size;
					if (removed != 0)
						this.annots[a - removed] = this.annots[a];
				}
			}
			Arrays.fill(this.annots, (this.annotCount - removed), this.annotCount, null); // free up references to help GC
			this.annotCount -= removed;
			this.maxAnnotSize = maxAnnotSize;
			this.removed.clear();
		}
		void cleanCaches() {
			for (int a = 0; a < this.annotCount; a++)
				this.annots[a].cleanCaches();
			this.cacheCleaningTrigger = new SoftReference(new CacheCleaningTrigger(this));
		}
		void clearCaches() {
			for (int a = 0; a < this.annotCount; a++)
				this.annots[a].clearCaches();
			this.cacheClearingTrigger = new SoftReference(new CacheClearingTrigger(this));
		}
	}
	
	private static class AnnotationCache extends LinkedHashMap {
		private int maxCapacity;
		AnnotationCache(int maxCapacity) {
			super(8, 0.9f, true);
			this.maxCapacity = maxCapacity;
		}
		AnnotationCacheEntry lookup(String key) {
			return ((AnnotationCacheEntry) this.get(key));
		}
		void cache(String key, AnnotationCacheEntry entry) {
			this.put(key, entry);
		}
		protected boolean removeEldestEntry(Entry eldest) {
			return (this.maxCapacity < this.size());
		}
	}
	
	private static class AnnotationCacheEntry {
		final AnnotationBase[] annotations;
		private final AnnotationList parent;
		private final int createModCount;
		AnnotationCacheEntry(AnnotationBase[] annotations, AnnotationList parent) {
			this.annotations = annotations;
			this.parent = parent;
			this.createModCount = this.parent.modCount;
		}
		boolean isInvalid(AnnotationList parent) {
			return ((parent != this.parent) || (this.createModCount != this.parent.modCount));
		}
		boolean isInvalid() {
			return (this.createModCount != this.parent.modCount);
		}
	}
	
	private static final AnnotationBase[] emptyAnnotationBaseArray = {};
	private class AnnotationStore {
		private AnnotationList annotations = new AnnotationList(null);
		private HashMap annotationsByType = new HashMap();
		private HashMap annotationsByID = new HashMap();
		
		private AnnotationList getAnnotationList(String type, boolean create) {
			if (type == null)
				return this.annotations;
			AnnotationList al = ((AnnotationList) this.annotationsByType.get(type));
			if ((al == null) && create) {
				al = new AnnotationList(type);
				this.annotationsByType.put(type, al);
			}
			return al;
		}
		
		synchronized void storeAnnotation(AnnotationBase ab) {
			if (this.annotationsByID.containsKey(ab.annotationId))
				return; // do not insert an Annotation twice
			this.annotations.addAnnotation(ab);
			this.getAnnotationList(ab.type, true).addAnnotation(ab);
			this.annotationsByID.put(ab.annotationId, ab);
		}
		
		synchronized AnnotationBase removeAnnotation(Annotation annot) {
			AnnotationBase ab;
			if (annot instanceof QueriableAnnotationView)
				ab = ((QueriableAnnotationView) annot).data;
			else return null;
			
			this.annotations.removeAnnotation(ab);
			AnnotationList typeAnnots = this.getAnnotationList(ab.type, false);
			if (typeAnnots != null) {
				typeAnnots.removeAnnotation(ab);
				if (typeAnnots.isEmpty())
					this.annotationsByType.remove(ab.type);
			}
			this.annotationsByID.remove(ab.annotationId);
			
			return ab;
		}
		
		void annotationIdChanged(AnnotationBase ab, String oldId) {
			this.annotationsByID.remove(oldId);
			this.annotationsByID.put(ab.annotationId, ab);
		}
		
		void annotationTypeChanged(AnnotationBase ab, String oldType) {
			AnnotationList oldTypeAnnots = this.getAnnotationList(oldType, false);
			if (oldTypeAnnots != null)
				oldTypeAnnots.removeAnnotation(ab);
			this.getAnnotationList(ab.type, true).addAnnotation(ab);
			this.annotations.annotationTypeChanged();
		}
		
		AnnotationBase getAnnotation(String id) {
			return ((AnnotationBase) this.annotationsByID.get(id));
		}
		
		AnnotationBase[] getAnnotations(String type) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotations());
		}
		
		AnnotationBase[] getAnnotations(AnnotationBase base, String type) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotationsIn(base));
		}
		
		AnnotationBase[] getAnnotationsSpanning(String type, int startIndex, int endIndex) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotations(startIndex, endIndex));
		}
		
		AnnotationBase[] getAnnotationsSpanning(AnnotationBase base, String type, int startIndex, int endIndex) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotationsIn(base, startIndex, endIndex));
		}
		
		AnnotationBase[] getAnnotationsOverlapping(String type, int startIndex, int endIndex) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotations((endIndex - 1), (startIndex + 1)));
		}
		
		AnnotationBase[] getAnnotationsOverlapping(AnnotationBase base, String type, int startIndex, int endIndex) {
			AnnotationList al = this.getAnnotationList(type, false);
			return ((al == null) ? emptyAnnotationBaseArray : al.getAnnotationsIn(base, (endIndex - 1), (startIndex + 1)));
		}
		
		String[] getAnnotationTypes() {
			TreeSet annotTypes = new TreeSet(this.annotationsByType.keySet());
			return ((String[]) annotTypes.toArray(new String[annotTypes.size()]));
		}
		
		String[] getAnnotationTypes(AnnotationBase base) {
			AnnotationBase[] annots = this.annotations.getAnnotationsIn(base);
			TreeSet annotTypes = new TreeSet();
			for (int a = 0; a < annots.length; a++)
				annotTypes.add(annots[a].type);
			return ((String[]) annotTypes.toArray(new String[annotTypes.size()]));
		}
		
		synchronized void tokenSequenceChanged(TokenSequenceEvent change) {
			
			//	prepare changes
			for (int a = 0; (a < this.annotations.size()); a++)
				this.annotations.getAnnotation(a).tokenSequeceChanged(change);
			
			//	commit changes
			for (int a = 0; (a < this.annotations.size()); a++)
				this.annotations.getAnnotation(a).commitChange();
			
			//	clean up
			this.cleanup();
		}
		
		void cleanup() {
			this.annotations.cleanup();
			for (Iterator atit = this.annotationsByType.keySet().iterator(); atit.hasNext();)
				((AnnotationList) this.annotationsByType.get(atit.next())).cleanup();
		}
		
		void clear() {
			this.annotations.clear();
			this.annotationsByType.clear();
			this.annotationsByID.clear();
		}
	}
//	/**	the storage for Annotations
//	 */
//	private class AnnotationStore {
//		
//		private ArrayList annotations = new ArrayList();
//		private HashSet annotationIDs = new HashSet();
//		
//		/**	store an Annotation
//		 * @param	ab	the Annotation to be stored
//		 */
//		synchronized void storeAnnotation(AnnotationBase ab) {
//			
//			//	do not insert an Annotation twice
//			if (this.containsAnnotation(ab))
//				return;
//			
//			//	add Annotation to content index
//			this.annotationIDs.add(ab.annotationId);
//			
//			//	start searching insertion point at end points
//			int left = 0;
//			int right = this.annotations.size();
//			
//			//	catch special cases (head or tail insert)
//			if (this.annotations.size() != 0) {
//				
//				//	larger than largest Annotation contained so far (check first, in order to save time when adding Annotations in ascending order, which happens more often than in descending order)
//				if (((AnnotationBase) this.annotations.get(right - 1)).compareTo(ab) <= 0) {
//					this.annotations.add(right, ab);
//					return;
//				}
//				
//				//	smaller than smallest annotation contained so far
//				else if (((AnnotationBase) this.annotations.get(0)).compareTo(ab) > 0) {
//					this.annotations.add(0, ab);
//					return;
//				}
//			}
//			
//			//	narrow insertion point with binary search down to a 4 interval
//			int c = -1;
//			int middle;
//			while ((right - left) > 4) {
//				middle = ((left + right) / 2);
//				c = ((AnnotationBase) this.annotations.get(middle)).compareTo(ab);
//				if (c < 0)
//					left = middle; // insertion right is left of middle
//				else if (c == 0) { // Annotation at middle is equal to inserted Annotation, search insertion point rightward to maintain insertion order
//					int lastC;
//					for (int i = middle; i < right; i++) {
//						lastC = c;
//						c = ((AnnotationBase) this.annotations.get(i)).compareTo(ab);
//						if (lastC <= 0 && c > 0) {
//							this.annotations.add(i, ab);
//							return;
//						}
//					}
//					this.annotations.add(right, ab);
//					return;
//				}
//				else right = middle;  // insertion point is left of middle
//			}
//			
//			//	insert with linear search in order to avoid special case treatments in binary search
//			int lastC;
//			for (int i = left; i < right; i++) {
//				lastC = c;
//				c = ((AnnotationBase) this.annotations.get(i)).compareTo(ab);
//				if (lastC <= 0 && c > 0) {
//					this.annotations.add(i, ab);
//					return;
//				}
//			}
//			this.annotations.add(right, ab);
//		}
//		
//		/**	retrieve all Annotations of a particular type contained in this AnnotationStore
//		 * @param	type	the type of the desired Annotations (specifying null will return all Annotations, regardless of their type)
//		 * @return all Annotations of the specified type contained in this AnnotationStore packed in an array
//		 */
//		AnnotationBase[] getAnnotations(String type) {
//			ArrayList list = new ArrayList();
//			AnnotationBase annot;
//			for (int a = 0; a < this.annotations.size(); a++) {
//				annot = ((AnnotationBase) this.annotations.get(a));
//				if ((type == null) || type.equals(annot.getType()))
//					list.add(annot);
//			}
//			return ((AnnotationBase[]) list.toArray(new AnnotationBase[list.size()]));
//		}
//		
//		/**	retrieve all Annotations of a particular type contained in this AnnotationStore that lay inside a given range
//		 * @param	type		the type of the desired Annotations (specifying null will return all Annotations, regardless of their type)
//		 * @param	absoluteStartIndex	the start index of the range
//		 * @param	size		the size of the range
//		 * @return all Annotations of the specified type contained in this AnnotationStore that lay within the specified range, packed in an array
//		 */
//		AnnotationBase[] getAnnotations(AnnotationBase base, String type) {
//			int baseAbsoluteStartIndex = base.getAbsoluteStartIndex();
//			int start = -1;
//			
//			//	binary search first annotation, start searching insertion point at end points
//			int left = 0;
//			int right = this.annotations.size();
//			
//			//	narrow staring point with binary search down to a 2 interval
//			int c = -1;
//			int middle;
//			while ((start == -1) && ((right - left) > 2)) {
//				middle = ((left + right) / 2);
//				
//				//	start linear search if interval down to 4
//				if ((right - left) < 4) c = 0;
//				else c = (((AnnotationBase) this.annotations.get(middle)).absoluteStartIndex - baseAbsoluteStartIndex);
//				
//				if (c < 0) left = middle; // starting point is right of middle
//				else if (c == 0) { // start of Annotation at middle is equal to base Annotation, search insertion point leftward not to miss an Annotation
//					start = middle;
//					while ((start != 0) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex == baseAbsoluteStartIndex))
//						start --; // count down to 0 at most
//				}
//				else right = middle;  // starting point is left of middle
//			}
//			
//			//	ensure valid index
//			start = Math.max(start, 0);
//			
//			//	move right to exact staring point
//			while ((start < this.annotations.size()) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex < baseAbsoluteStartIndex))
//				start++;
//			
//			int absoluteStartIndexLimit = base.getAbsoluteStartIndex() + base.size();
//			ArrayList annotationList = new ArrayList();
//			AnnotationBase ab;
//			for (int a = start; a < this.annotations.size(); a++) {
//				ab = ((AnnotationBase) this.annotations.get(a));
//				if (((type == null) || type.equals(ab.type)) && (ab.absoluteStartIndex >= baseAbsoluteStartIndex) && (ab.getEndIndex() <= absoluteStartIndexLimit))
//					annotationList.add(ab);
//				if (ab.absoluteStartIndex >= absoluteStartIndexLimit)
//					return ((AnnotationBase[]) annotationList.toArray(new AnnotationBase[annotationList.size()]));
//			}
//			return ((AnnotationBase[]) annotationList.toArray(new AnnotationBase[annotationList.size()]));
//		}
//		
//		/**	retrieve all Annotations contained in this AnnotationStore that lay inside a given range
//		 * @param	absoluteStartIndex	the start index of the range
//		 * @param	size		the size of the range
//		 * @return all Annotations contained in this AnnotationStore that lay inside the specified range, packed in an array
//		 */
//		String[] getAnnotationTypes() {
//			StringVector types = new StringVector();
//			for (int a = 0; a < this.annotations.size(); a++)
//				types.addElementIgnoreDuplicates(((AnnotationBase) this.annotations.get(a)).getType());
//			types.sortLexicographically(false, false);
//			return types.toStringArray();
//		}
//		
//		/**	retrieve all Annotations contained in this AnnotationStore that lay inside a given range
//		 * @param	absoluteStartIndex	the start index of the range
//		 * @param	size		the size of the range
//		 * @return all Annotations contained in this AnnotationStore that lay inside the specified range, packed in an array
//		 */
//		String[] getAnnotationTypes(AnnotationBase base) {
//			int baseAbsoluteStartIndex = base.getAbsoluteStartIndex();
//			int start = -1;
//			
//			//	binary search first annotation, start searching insertion point at end points
//			int left = 0;
//			int right = this.annotations.size();
//			
//			//	narrow staring point with binary search down to a 2 interval
//			int c = -1;
//			int middle;
//			while ((start == -1) && ((right - left) > 2)) {
//				middle = ((left + right) / 2);
//				
//				//	start linear search if interval down to 4
//				if ((right - left) < 4) c = 0;
//				else c = (((AnnotationBase) this.annotations.get(middle)).absoluteStartIndex - baseAbsoluteStartIndex);
//				
//				if (c < 0) left = middle; // starting point is right of middle
//				else if (c == 0) { // start of Annotation at middle is equal to base Annotation, search insertion point leftward not to miss an Annotation
//					start = middle;
//					while ((start != 0) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex == baseAbsoluteStartIndex))
//						start --; // count down to 0 at most
//				} else right = middle;  // starting point is left of middle
//			}
//			
//			//	ensure valid index
//			start = Math.max(start, 0);
//			
//			//	move right to exact staring point
//			while ((start < this.annotations.size()) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex < baseAbsoluteStartIndex))
//				start++;
//			
//			//	collect types
//			int absoluteStartIndexLimit = base.getAbsoluteStartIndex() + base.size();
//			StringVector types = new StringVector();
//			AnnotationBase ab;
//			for (int a = start; a < this.annotations.size(); a++) {
//				ab = ((AnnotationBase) this.annotations.get(a));
//				if ((ab.absoluteStartIndex >= baseAbsoluteStartIndex) && (ab.getEndIndex() <= absoluteStartIndexLimit)) types.addElementIgnoreDuplicates(ab.type);
//				if (ab.absoluteStartIndex >= absoluteStartIndexLimit) return types.toStringArray();
//			}
//			return types.toStringArray();
//		}
//		
//		/**	remove an Annotation from this AnnotationStore
//		 * @param	annotation	the Annotation to be removed
//		 * @return the Annotation that was just removed, or null, if the Annotation was not contained in this AnnotationStore
//		 */
//		AnnotationBase removeAnnotation(int baseStartIndex, Annotation annotation) {
//			
//			//	find Annotation
//			AnnotationBase ab = null;
//			
//			int absoluteStartIndex = baseStartIndex + annotation.getStartIndex(); // start index of base to remove
//			int annotationSize = annotation.size();
//			int start = -1;
//			
//			//	binary search first annotation, start searching insertion point at end points
//			int left = 0;
//			int right = this.annotations.size();
//			
//			//	narrow staring point with binary search down to a 2 interval
//			int c = -1;
//			int middle;
//			while ((start == -1) && ((right - left) > 2)) {
//				middle = ((left + right) / 2);
//				
//				//	start linear search if interval down to 4
//				if ((right - left) < 4)
//					c = 0;
//				else c = (((AnnotationBase) this.annotations.get(middle)).absoluteStartIndex - absoluteStartIndex);
//				
//				if (c < 0)
//					left = middle; // starting point is right of middle
//				else if (c == 0) { // start of Annotation at middle is equal to base Annotation, search insertion point leftward not to miss an Annotation
//					start = middle;
//					while ((start != 0) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex == absoluteStartIndex))
//						start --; // count down to 0 at most
//				}
//				else right = middle;  // starting point is left of middle
//			}
//			
//			//	ensure valid index
//			start = Math.min(start, (this.annotations.size() - 1));
//			
//			//	move left to exact staring point
//			while ((start > -1) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex >= absoluteStartIndex))
//				start--;
//			
//			//	ensure valid index
//			start = Math.max(start, 0);
//			
//			//	move rigth to exact staring point
//			while ((start < this.annotations.size()) && (((AnnotationBase) this.annotations.get(start)).absoluteStartIndex < absoluteStartIndex)) start++;
//			
//			//	find Annotation
//			for (int a = start; a < this.annotations.size(); a++) {
//				ab = ((AnnotationBase) this.annotations.get(a));
//				
//				//	annotation not found
//				if (ab.absoluteStartIndex > absoluteStartIndex)
//					return null;
//				
//				//	matching start index & size
//				else if ((ab.absoluteStartIndex == absoluteStartIndex) && (ab.size == annotationSize)) {
//					
//					//	ID match, do not attempt type / attribute match
//					if (this.annotationIDs.contains(annotation.getAnnotationID())) {
//						
//						//	matching IDs
//						if (ab.annotationId.equals(annotation.getAnnotationID())) {
//							this.annotations.remove(a);
//							this.annotationIDs.remove(ab.annotationId);
//							return ab;
//						}
//					}
//					
//					//	type / attribute match
//					else if (ab.type.equals(annotation.getType()) && AttributeUtils.hasEqualAttributes(ab, annotation)) {
//						this.annotations.remove(a);
//						this.annotationIDs.remove(ab.annotationId);
//						return ab;
//					}
//				}
//			}
//			
//			//	Annotation not found
//			return ab;
//		}
//		
//		/**	check if this store contains an Annotation
//		 * @param	ab	the Annotation to search for
//		 * @return true if and only if this AnnotationStore contains the specified Annotation
//		 */
//		boolean containsAnnotation(AnnotationBase ab) {
//			return ((ab != null) && this.annotationIDs.contains(ab.annotationId));
//		}
//		
//		/* (non-Javadoc)
//		 * @see de.gamta.TokenSequenceListener#tokenSequenceChanged(de.gamta.MutableTokenSequence.TokenSequenceEvent)
//		 */
//		synchronized void tokenSequenceChanged(TokenSequenceEvent change) {
//			
//			//	prepare changes
//			for (int a = 0; (a < this.annotations.size()); a++)
//				((AnnotationBase) this.annotations.get(a)).tokenSequeceChanged(change);
//			
//			//	commit changes
//			for (int a = 0; (a < this.annotations.size()); a++)
//				((AnnotationBase) this.annotations.get(a)).commitChange();
//			
//			//	clean up
//			this.cleanup();
//		}
//		
//		/**	remove all empty Annotations from the store
//		 */
//		void cleanup() {
//			int a = 0;
//			while (a < this.annotations.size()) {
//				AnnotationBase ab = ((AnnotationBase) this.annotations.get(a));
//				if (ab.size() <= 0) {
//					this.annotations.remove(a);
//					if (AnnotationBase.DEBUG_CHANGE || ab.printDebugInfo())
//						System.out.println("REMOVED: " + ab.type + " at " + ab.absoluteStartIndex + " sized " + ab.size);
//				}
//				else a++;
//			}
//		}
//		
//		/**	delete all Annotations contained in this AnnotationStore
//		 */
//		void clear() {
//			this.annotations.clear();
//			this.annotationIDs.clear();
//		}
//	}
}
