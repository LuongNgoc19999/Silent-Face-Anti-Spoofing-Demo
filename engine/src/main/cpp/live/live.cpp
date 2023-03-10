//
// Created by yuanhao on 20-6-12.
//
#include <opencv2/opencv.hpp>
#include <opencv2/dnn/dnn.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "live.h"
#include "../android_log.h"
#include <iostream>
using namespace std;
using namespace cv;
using namespace cv::dnn;

Live::Live() {
    thread_num_ = 8;

    option_.lightmode = true;
    option_.num_threads = thread_num_;
}

Live::~Live() {
    for (int i = 0; i < nets_.size(); ++i) {
        nets_[i]->clear();
        delete nets_[i];
    }
    nets_.clear();
}

int Live::LoadModel(AAssetManager *assetManager, std::vector<ModelConfig> &configs) {
    configs_ = configs;
    model_num_ = static_cast<int>(configs_.size());
//    LOG_ERR("checkkkkk222.");

    for (int i = 0; i < model_num_; ++i) {
        ncnn::Net *net = new ncnn::Net();

        net->opt = option_;
        std::string param = "live/" + configs_[i].name + ".param";
        std::string model = "live/" + configs_[i].name + ".bin";
        int ret = net->load_param(assetManager, param.c_str());

        if (ret != 0) {
            LOG_ERR("LiveBody load param failed.");
            return -2 * (i) - 1;
        }

        ret = net->load_model(assetManager, model.c_str());
        if (ret != 0) {
            LOG_ERR("LiveBody load model failed.");
            return -2 * (i + 1);
        }
        nets_.emplace_back(net);
    }
    return 0;
}

float Live::Detect(cv::Mat &src, FaceBox &box) {
    float confidence = 0.f;
    for (int i = 0; i < model_num_; i++) {
        cv::Mat roi;
        if(configs_[i].org_resize) {

            cv::resize(src, roi, cv::Size(configs_[i].width, configs_[i].height));
        } else {

            cv::Rect rect = CalculateBox(box, src.cols, src.rows, configs_[i]);
            // roi resize
            cv::resize(src(rect), roi, cv::Size(configs_[i].width, configs_[i].height));

        }
        LOG_ERR("checkkkkk_Input shape: %d x %d x %d x %d\n", roi.size[0], roi.size[1], roi.channels() , roi.rows);

        if (i == 2) {
            cv::cvtColor(roi, roi, cv::COLOR_BGR2RGB);
        }

        ncnn::Mat in = ncnn::Mat::from_pixels(roi.data, ncnn::Mat::PIXEL_BGR, roi.cols, roi.rows);


        // inference
        ncnn::Extractor extractor = nets_[i]->create_extractor();
        extractor.set_light_mode(true);
        extractor.set_num_threads(thread_num_);

        extractor.input(net_input_name_.c_str(), in);

        ncnn::Mat out;
//        LOG_ERR("checkkkkk%f", in);

        extractor.extract(net_output_name_.c_str(), out); //bug
        LOG_ERR("checkkkkk%d", out.dims);

        if (i == 2) {
            confidence += out.row(0)[0];
//            confidence += out.row(0)[0];

        }
        else {
            confidence += out.row(0)[1];

        }

    }
    confidence /= model_num_ ;

    box.confidence = confidence;
    return confidence;
}

cv::Rect Live::CalculateBox(FaceBox &box, int w, int h, ModelConfig &config) {
    int x = static_cast<int>(box.x1);
    int y = static_cast<int>(box.y1);
    int box_width = static_cast<int>(box.x2 - box.x1 + 1);
    int box_height = static_cast<int>(box.y2 - box.y1 + 1);

    int shift_x = static_cast<int>(box_width * config.shift_x);
    int shift_y = static_cast<int>(box_height * config.shift_y);

    float scale = std::min(
            config.scale,
            std::min((w - 1) / (float) box_width, (h - 1) / (float) box_height)
    );

    int box_center_x = box_width / 2 + x;
    int box_center_y = box_height / 2 + y;

    int new_width = static_cast<int>(box_width * scale);
    int new_height = static_cast<int>(box_height * scale);

    int left_top_x = box_center_x - new_width / 2 + shift_x;
    int left_top_y = box_center_y - new_height / 2 + shift_y;
    int right_bottom_x = box_center_x + new_width / 2 + shift_x;
    int right_bottom_y = box_center_y + new_height / 2 + shift_y;

    if (left_top_x < 0) {
        right_bottom_x -= left_top_x;
        left_top_x = 0;
    }

    if (left_top_y < 0) {
        right_bottom_y -= left_top_y;
        left_top_y = 0;
    }

    if (right_bottom_x >= w) {
        int s = right_bottom_x - w + 1;
        left_top_x -= s;
        right_bottom_x -= s;
    }

    if (right_bottom_y >= h) {
        int s = right_bottom_y - h + 1;
        left_top_y -= s;
        right_bottom_y -= s;
    }

    return cv::Rect(left_top_x, left_top_y, new_width, new_height);
}
