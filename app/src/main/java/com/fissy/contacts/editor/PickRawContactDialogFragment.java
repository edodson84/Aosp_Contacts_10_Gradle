package com.fissy.contacts.editor;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;

import com.fissy.contacts.ContactPhotoManager;
import com.fissy.contacts.R;
import com.fissy.contacts.activities.ContactSelectionActivity;
import com.fissy.contacts.editor.PickRawContactLoader.RawContact;
import com.fissy.contacts.editor.PickRawContactLoader.RawContactsMetadata;
import com.fissy.contacts.list.UiIntentActions;
import com.fissy.contacts.logging.EditorEvent;
import com.fissy.contacts.logging.Logger;
import com.fissy.contacts.model.AccountTypeManager;
import com.fissy.contacts.model.account.AccountDisplayInfo;
import com.fissy.contacts.model.account.AccountDisplayInfoFactory;
import com.fissy.contacts.model.account.AccountInfo;
import com.fissy.contacts.model.account.AccountType;
import com.fissy.contacts.model.account.AccountWithDataSet;
import com.fissy.contacts.model.account.GoogleAccountType;
import com.fissy.contacts.preference.ContactsPreferences;

/**
 * Should only be started from an activity that implements {@link PickRawContactListener}.
 * Dialog containing the raw contacts that make up a contact. On selection the editor is loaded
 * for the chosen raw contact.
 */
public class PickRawContactDialogFragment extends DialogFragment {
    private static final String ARGS_RAW_CONTACTS_METADATA = "rawContactsMetadata";
    private static final int REQUEST_CODE_JOIN = 3;

    public interface PickRawContactListener {
        void onPickRawContact(long rawContactId);
    }

    /**
     * Used to list the account info for the given raw contacts list.
     */
    private final class RawContactAccountListAdapter extends BaseAdapter {
        private final LayoutInflater mInflater;
        private final Context mContext;
        private final RawContactsMetadata mRawContactsMetadata;
        private final AccountTypeManager mAccountTypeManager;
        private final ContactsPreferences mPreferences;

        public RawContactAccountListAdapter(Context context,
                RawContactsMetadata rawContactsMetadata) {
            mContext = context;
            mInflater = LayoutInflater.from(context);
            mAccountTypeManager = AccountTypeManager.getInstance(context);
            mPreferences = new ContactsPreferences(context);
            mRawContactsMetadata = rawContactsMetadata;
        }

        @Override
        public int getCount() {
            return mRawContactsMetadata.rawContacts.size();
        }

        @Override
        public Object getItem(int position) {
            return mRawContactsMetadata.rawContacts.get(position);
        }

        @Override
        public long getItemId(int position) {
            return mRawContactsMetadata.rawContacts.get(position).id;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view;
            final RawContactViewHolder holder;
            if (convertView == null) {
                view = mInflater.inflate(R.layout.raw_contact_list_item, parent, false);
                holder = new RawContactViewHolder();
                holder.displayName = (TextView) view.findViewById(R.id.display_name);
                holder.accountName = (TextView) view.findViewById(R.id.account_name);
                holder.accountIcon = (ImageView) view.findViewById(R.id.account_icon);
                holder.photo = (ImageView) view.findViewById(R.id.photo);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (RawContactViewHolder) view.getTag();
            }
            final RawContact rawContact = mRawContactsMetadata.rawContacts.get(position);
            final AccountType account = mAccountTypeManager.getAccountType(rawContact.accountType,
                    rawContact.accountDataSet);

            String displayName =
                    mPreferences.getDisplayOrder() == ContactsPreferences.DISPLAY_ORDER_PRIMARY
                            ? rawContact.displayName : rawContact.displayNameAlt;

            if (TextUtils.isEmpty(displayName)) {
                displayName = mContext.getString(R.string.missing_name);
            }
            holder.displayName.setText(displayName);

            final String accountDisplayLabel;

            // Use the same string as editor if it's an editable user profile raw contact.
            if (mRawContactsMetadata.isUserProfile && account.areContactsWritable()) {
                final AccountInfo accountInfo =
                        AccountTypeManager.getInstance(getContext()).getAccountInfoForAccount(
                                new AccountWithDataSet(rawContact.accountName,
                                        rawContact.accountType, rawContact.accountDataSet));
                accountDisplayLabel = EditorUiUtils.getAccountHeaderLabelForMyProfile(mContext,
                        accountInfo);
            } else if (GoogleAccountType.ACCOUNT_TYPE.equals(rawContact.accountType)
                    && account.dataSet == null) {
                // Focus Google accounts have the account name shown
                accountDisplayLabel = rawContact.accountName;
            } else {
                accountDisplayLabel = account.getDisplayLabel(mContext).toString();
            }

            holder.accountName.setText(accountDisplayLabel);
            holder.accountIcon.setImageDrawable(account.getDisplayIcon(mContext));
            final ContactPhotoManager.DefaultImageRequest
                    request = new ContactPhotoManager.DefaultImageRequest(
                    displayName, String.valueOf(rawContact.id), /* isCircular = */ true);

            ContactPhotoManager.getInstance(mContext).loadThumbnail(holder.photo,
                    rawContact.photoId,
                    /* darkTheme = */ false,
                    /* isCircular = */ true,
                    request);
            return view;
        }

        class RawContactViewHolder {
            TextView displayName;
            TextView accountName;
            ImageView accountIcon;
            ImageView photo;
        }
    }

    private ListAdapter mAdapter;
    private boolean mShouldFinishActivity = true;

    public static PickRawContactDialogFragment getInstance(RawContactsMetadata metadata) {
        final PickRawContactDialogFragment fragment = new PickRawContactDialogFragment();
        final Bundle args = new Bundle();
        args.putParcelable(ARGS_RAW_CONTACTS_METADATA, metadata);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        if (!(getActivity() instanceof PickRawContactListener)) {
            throw new IllegalArgumentException(
                    "Host activity doesn't implement PickRawContactListener");
        }
        final Bundle args = getArguments();
        if (args == null) {
            throw new IllegalArgumentException("Dialog created with no arguments");
        }

        final RawContactsMetadata metadata = args.getParcelable(ARGS_RAW_CONTACTS_METADATA);
        if (metadata == null) {
            throw new IllegalArgumentException("Dialog created with null RawContactsMetadata");
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        mAdapter = new RawContactAccountListAdapter(getContext(), metadata);
        if (metadata.showReadOnly) {
            builder.setTitle(R.string.contact_editor_pick_linked_contact_dialog_title);
            // Only provide link editing options for non-user profile contacts.
            if (!metadata.isUserProfile) {
                builder.setPositiveButton(R.string.contact_editor_add_linked_contact,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mShouldFinishActivity = false;
                                final Intent intent = new Intent(getActivity(),
                                        ContactSelectionActivity.class);
                                intent.setAction(UiIntentActions.PICK_JOIN_CONTACT_ACTION);
                                intent.putExtra(UiIntentActions.TARGET_CONTACT_ID_EXTRA_KEY,
                                        metadata.contactId);
                                getActivity().startActivityForResult(intent, REQUEST_CODE_JOIN);
                            }
                        });
                builder.setNegativeButton(R.string.contact_editor_unlink_contacts,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                mShouldFinishActivity = false;
                                final SplitContactConfirmationDialogFragment splitDialog = new
                                        SplitContactConfirmationDialogFragment();
                                splitDialog.show(getActivity().getFragmentManager(),
                                        SplitContactConfirmationDialogFragment.TAG);
                            }
                        });
            }
        } else {
            builder.setTitle(R.string.contact_editor_pick_raw_contact_to_edit_dialog_title);
        }
        builder.setAdapter(mAdapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                final long rawContactId = mAdapter.getItemId(which);
                ((PickRawContactListener) getActivity()).onPickRawContact(rawContactId);
            }
        });
        builder.setCancelable(true);
        if (savedInstanceState == null) {
            Logger.logEditorEvent(EditorEvent.EventType.SHOW_RAW_CONTACT_PICKER,
                    /* numberRawContacts */ mAdapter.getCount());
        }
        return builder.create();
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        if (mShouldFinishActivity) {
            finishActivity();
        }
    }

    @Override
    public Context getContext() {
        return getActivity();
    }

    private void finishActivity() {
        if (getActivity() != null && !getActivity().isFinishing()) {
            getActivity().finish();
        }
    }
}
