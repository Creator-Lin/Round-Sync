package quarkcookie

type respStatus struct {
	Status  int    `json:"status"`
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type fileItem struct {
	Fid        string `json:"fid"`
	FileName   string `json:"file_name"`
	Category   int    `json:"category"`
	Size       int64  `json:"size"`
	LCreatedAt int64  `json:"l_created_at"`
	LUpdatedAt int64  `json:"l_updated_at"`
	File       bool   `json:"file"`
	CreatedAt  int64  `json:"created_at"`
	UpdatedAt  int64  `json:"updated_at"`
}

type sortResp struct {
	respStatus
	Data struct {
		List []fileItem `json:"list"`
	} `json:"data"`
	Metadata struct {
		Size  int    `json:"_size"`
		Page  int    `json:"_page"`
		Count int    `json:"_count"`
		Total int    `json:"_total"`
		Way   string `json:"way"`
	} `json:"metadata"`
}

type downResp struct {
	respStatus
	Data []struct {
		DownloadURL string `json:"download_url"`
	} `json:"data"`
}

type transcodingResp struct {
	respStatus
	Data struct {
		DefaultResolution       string `json:"default_resolution"`
		OriginDefaultResolution string `json:"origin_default_resolution"`
		VideoList               []struct {
			Resolution string `json:"resolution"`
			VideoInfo  struct {
				Size int64  `json:"size"`
				URL  string `json:"url"`
			} `json:"video_info,omitempty"`
		} `json:"video_list"`
		FileName string `json:"file_name"`
		Size     int64  `json:"size"`
	} `json:"data"`
}

type upPreResp struct {
	respStatus
	Data struct {
		TaskID    string `json:"task_id"`
		Finish    bool   `json:"finish"`
		UploadID  string `json:"upload_id"`
		ObjKey    string `json:"obj_key"`
		UploadURL string `json:"upload_url"`
		Fid       string `json:"fid"`
		Bucket    string `json:"bucket"`
		Callback  struct {
			CallbackURL  string `json:"callbackUrl"`
			CallbackBody string `json:"callbackBody"`
		} `json:"callback"`
		FormatType string `json:"format_type"`
		Size       int    `json:"size"`
		AuthInfo   string `json:"auth_info"`
	} `json:"data"`
	Metadata struct {
		PartThread int    `json:"part_thread"`
		Acc2       string `json:"acc2"`
		Acc1       string `json:"acc1"`
		PartSize   int    `json:"part_size"`
	} `json:"metadata"`
}

type hashResp struct {
	respStatus
	Data struct {
		Finish     bool   `json:"finish"`
		Fid        string `json:"fid"`
		Thumbnail  string `json:"thumbnail"`
		FormatType string `json:"format_type"`
	} `json:"data"`
}

type upAuthResp struct {
	respStatus
	Data struct {
		AuthKey string `json:"auth_key"`
		Speed   int    `json:"speed"`
	} `json:"data"`
}

type memberResp struct {
	respStatus
	Data struct {
		SecretUseCapacity   int64 `json:"secret_use_capacity"`
		UseCapacity         int64 `json:"use_capacity"`
		SecretTotalCapacity int64 `json:"secret_total_capacity"`
		TotalCapacity       int64 `json:"total_capacity"`
	} `json:"data"`
}
