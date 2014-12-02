/*
 * The contents of this file are subject to the OpenMRS Public License
 * Version 1.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://license.openmrs.org
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations
 * under the License.
 *
 * Copyright (C) OpenMRS, LLC.  All Rights Reserved.
 */

package org.openmrs.client.net;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.toolbox.Volley;

import org.kxml2.io.KXmlParser;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
import org.odk.collect.android.logic.FormDetails;
import org.odk.collect.android.openmrs.provider.OpenMRSFormsProviderAPI;
import org.odk.collect.android.utilities.FileUtils;
import org.openmrs.client.application.OpenMRS;
import org.openmrs.client.net.volley.wrappers.StringRequestDecorator;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.openmrs.client.utilities.ApplicationConstants.API;

public class FormsManger extends BaseManager {
    private static final String TEMP_DOWNLOAD_EXTENSION = ".tempDownload";

    private StringRequestDecorator mRequestDecorator;

    public FormsManger(Context context) {
        super(context);
    }

    public void getAvailableFormsList() {
        RequestQueue queue = Volley.newRequestQueue(mContext);
        String xFormsListURL = mOpenMRS.getServerUrl() + API.XFORM_ENDPOINT + API.FORM_LIST;
        mRequestDecorator = new StringRequestDecorator(Request.Method.GET, xFormsListURL,
                new Response.Listener<String>()
                {
                    @Override
                    public void onResponse(String response) {
                        mOpenMRS.getOpenMRSLogger().d(response.toString());
                        Document xFormsDoc = writeResponseToDoc(response);
                        List<FormDetails> formList = getFormDetails(xFormsDoc);
                        if (!formList.isEmpty()) {
                            for (FormDetails fd: formList) {
                                downloadForm(fd.formName, fd.downloadUrl);
                            }
                        }
                    }
                },
                new GeneralErrorListenerImpl(mContext)
        );
        queue.add(mRequestDecorator);
    }

    public void downloadForm(final String formName, String downloadUrl) {
        RequestQueue queue = Volley.newRequestQueue(mContext);
        String xFormsListURL = mOpenMRS.getServerUrl() + API.XFORM_ENDPOINT + downloadUrl;
        mRequestDecorator = new StringRequestDecorator(Request.Method.GET, xFormsListURL,
                new Response.Listener<String>()
                {
                    @Override
                    public void onResponse(String response) {
                        mOpenMRS.getOpenMRSLogger().d(response.toString());
                        try {
                            writeResponseToFile(formName, response);
                        } catch (IOException e) {
                            mOpenMRS.getOpenMRSLogger().d(e.toString());
                        }
                    }
                },
                new GeneralErrorListenerImpl(mContext)
        );
        queue.add(mRequestDecorator);
    }

    private List<FormDetails> getFormDetails(Document xFormsDoc) {
        List<FormDetails> formList = new ArrayList<FormDetails>();
        int nElements = xFormsDoc.getChildCount();
        for (int i = 0; i < nElements; ++i) {
            if (xFormsDoc.getType(i) != Element.ELEMENT) {
                // e.g., whitespace (text)
                continue;
            }
            Element xFormElement = (Element) xFormsDoc.getElement(i);

            String formId = null;
            String formName = null;
            String formUrl = null;
            int fieldCount = xFormElement.getChildCount();
            for (int j = 0; j < fieldCount; ++j) {
                if (xFormElement.getType(j) != Element.ELEMENT) {
                    // whitespace
                    continue;
                }
                Element child = xFormElement.getElement(j);
                if (child.getName().equals("form")) {
                    formName = child.getText(0);
                    if (formName != null && formName.length() == 0) {
                        formName = null;
                    }
                }
                // zero value cause url is the only attribute
                if (child.getAttributeName(0).equals("url")) {
                    formUrl = child.getAttributeValue(0);
                    formUrl = formUrl.substring(formUrl.lastIndexOf('/') + 1);
                    if (formUrl.length() == 0) {
                        formUrl = null;
                    } else {
                        formId = formUrl.substring(formUrl.lastIndexOf('=') + 1);
                    }
                }

                formList.add(new FormDetails(formName, formUrl, null, formId, null));
            }
        }
        return formList;
    }

    private Document writeResponseToDoc(String response) {
        Document xFormsDoc = null;
        try {
            xFormsDoc = new Document();
            KXmlParser parser = new KXmlParser();
            parser.setInput(new StringReader(response));
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,
                    true);
            xFormsDoc.parse(parser);
        } catch (IOException e) {
            mOpenMRS.getOpenMRSLogger().d(e.toString());
        } catch (XmlPullParserException e) {
            mOpenMRS.getOpenMRSLogger().d(e.toString());
        }
        return xFormsDoc;
    }

    private void writeResponseToFile(String formName, String response) throws IOException {
        String rootName = formName.replaceAll("[^\\p{L}\\p{Digit}]", " ");
        rootName = rootName.replaceAll("\\p{javaWhitespace}+", " ");
        rootName = rootName.trim();

        String path = OpenMRS.FORMS_PATH + File.separator + rootName + ".xml";
        int i = 2;
        File file = new File(path);
        while (file.exists()) {
            path = OpenMRS.FORMS_PATH + File.separator + rootName + "_" + i + ".xml";
            file = new File(path);
            i++;
        }

        File tempFile =  File.createTempFile(formName, TEMP_DOWNLOAD_EXTENSION, new File(OpenMRS.CACHE_PATH));
        FileOutputStream outputStream;

        try {
            outputStream = mContext.openFileOutput(formName, Context.MODE_PRIVATE);
            outputStream.write(response.getBytes());
            outputStream.close();
        } catch (IOException e) {
            mOpenMRS.getOpenMRSLogger().d(e.toString());
        }

        FileUtils.deleteAndReport(file);

        String errorMessage = FileUtils.copyFile(tempFile, file);

        if (file.exists()) {
            FileUtils.deleteAndReport(tempFile);
        } else {
            throw new RuntimeException(errorMessage);
        }

        saveOrUpdateForm(file);
    }

    private void saveOrUpdateForm(File formFile) {
        Cursor cursor = null;
        boolean isNew;

        String formFilePath = formFile.getAbsolutePath();

        try {
            String[] selectionArgs = {
                    formFile.getAbsolutePath()
            };
            String selection = OpenMRSFormsProviderAPI.FormsColumns.FORM_FILE_PATH + "=?";
            cursor = OpenMRS.getInstance()
                    .getContentResolver()
                    .query(OpenMRSFormsProviderAPI.FormsColumns.CONTENT_URI, null, selection, selectionArgs,
                            null);

            isNew = cursor.getCount() <= 0;

            if (isNew) {
                // doesn't exist, so insert it
                ContentValues v = new ContentValues();

                v.put(OpenMRSFormsProviderAPI.FormsColumns.FORM_FILE_PATH, formFilePath);

                HashMap<String, String> formInfo = FileUtils.parseXML(formFile);

                v.put(OpenMRSFormsProviderAPI.FormsColumns.DISPLAY_NAME, formInfo.get(FileUtils.TITLE));
                v.put(OpenMRSFormsProviderAPI.FormsColumns.JR_VERSION, formInfo.get(FileUtils.VERSION));
                v.put(OpenMRSFormsProviderAPI.FormsColumns.JR_FORM_ID, formInfo.get(FileUtils.FORMID));
                v.put(OpenMRSFormsProviderAPI.FormsColumns.SUBMISSION_URI, formInfo.get(FileUtils.SUBMISSIONURI));
                v.put(OpenMRSFormsProviderAPI.FormsColumns.BASE64_RSA_PUBLIC_KEY, formInfo.get(FileUtils.BASE64_RSA_PUBLIC_KEY));
                OpenMRS.getInstance().getContentResolver()
                        .insert(OpenMRSFormsProviderAPI.FormsColumns.CONTENT_URI, v);

            } else {
                cursor.moveToFirst();
                Uri.withAppendedPath(OpenMRSFormsProviderAPI.FormsColumns.CONTENT_URI,
                        cursor.getString(cursor.getColumnIndex(OpenMRSFormsProviderAPI.FormsColumns._ID)));
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
